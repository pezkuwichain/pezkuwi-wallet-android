package io.novafoundation.nova.feature_account_impl.data.repository.datasource.migration

import io.novafoundation.nova.common.data.secrets.v2.ChainAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.MetaAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.SecretStoreV2
import io.novafoundation.nova.common.data.secrets.v2.entropy
import io.novafoundation.nova.common.data.secrets.v2.ethereumDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.ethereumKeypair
import io.novafoundation.nova.common.data.secrets.v2.mapKeypairStructToKeypair
import io.novafoundation.nova.common.data.secrets.v2.seed
import io.novafoundation.nova.common.data.secrets.v2.substrateDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.substrateKeypair
import io.novafoundation.nova.common.data.secrets.v2.tronKeypair
import io.novafoundation.nova.common.data.storage.Preferences
import io.novafoundation.nova.common.utils.tronPublicKeyToAccountId
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.dao.updateMetaAccount
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novafoundation.nova.feature_account_impl.data.secrets.AccountSecretsFactory
import io.novafoundation.nova.feature_account_impl.data.secrets.TRON_DEFAULT_DERIVATION_PATH
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_TRON_ADDRESS_BACKFILL_DONE = "tron_address_backfill_1_1_3"

/**
 * One-time backfill for accounts created before Tron support existed. `73_74_AddTronSupport` (the migration
 * that added `meta_accounts.tronPublicKey`/`tronAddress`) is, like every other migration in this codebase, pure
 * `ALTER TABLE` - it never derives a value for pre-existing rows. `tronAddress` is otherwise only ever set once,
 * at fresh-mnemonic-creation time in [io.novafoundation.nova.feature_account_impl.data.secrets.AccountSecretsFactory.metaAccountSecrets],
 * so without this backfill `MetaAccount.hasAccountIn(tronChain)` (`tronAddress != null`) permanently returns
 * false for every pre-existing seed-derived wallet, which makes `BalancesUpdateSystem` skip Tron entirely for
 * that account - no address, no balance, no send, with no error surfaced anywhere. Found via manual testing
 * against a real pre-Tron production wallet; no automated test catches this because every automated test
 * creates a fresh (post-Tron) account.
 *
 * Only touches accounts that are:
 * - `Type.SECRETS` (mnemonic-derived) - watch-only/Ledger/Json/multisig/proxied accounts never had a
 *   Tron-capable mnemonic and are correctly left with `tronAddress == null` forever, same as they already are
 *   for Ethereum.
 * - still holding their `Entropy` in [SecretStoreV2] - an account imported from a raw seed/keypair rather than
 *   a mnemonic has no entropy either and is likewise correctly left alone.
 * - missing a `TronKeypair` - i.e. not already backfilled and not created after Tron support shipped.
 *
 * The Tron keypair is derived via [AccountSecretsFactory.chainAccountSecrets] with `isEthereum = true` (Tron
 * reuses the exact same secp256k1/BIP32 derivation as Ethereum, just under its own SLIP-44 coin-type-195 path -
 * see that class's own doc comment) at [TRON_DEFAULT_DERIVATION_PATH], the same call this codebase's own
 * `metaAccountSecrets()` makes for a fresh account - so a backfilled account ends up with byte-for-byte the
 * same Tron address it would have gotten had it been created today, not a separately-reimplemented derivation.
 */
class TronAddressBackfillMigration(
    private val preferences: Preferences,
    private val secretStoreV2: SecretStoreV2,
    private val metaAccountDao: MetaAccountDao,
    private val accountSecretsFactory: AccountSecretsFactory,
) {

    suspend fun migrationNeeded(): Boolean = withContext(Dispatchers.Default) {
        !preferences.getBoolean(PREFS_TRON_ADDRESS_BACKFILL_DONE, false)
    }

    suspend fun migrate() = withContext(Dispatchers.Default) {
        val secretsAccounts = metaAccountDao.getMetaAccounts().filter { it.type == MetaAccountLocal.Type.SECRETS }

        secretsAccounts.forEach { account ->
            backfillIfNeeded(account)
        }

        preferences.putBoolean(PREFS_TRON_ADDRESS_BACKFILL_DONE, true)
    }

    private suspend fun backfillIfNeeded(account: MetaAccountLocal) {
        val secrets = secretStoreV2.getMetaAccountSecrets(account.id) ?: return
        val entropy = secrets.entropy ?: return
        if (secrets.tronKeypair != null) return
        val substrateCryptoType = account.substrateCryptoType ?: return

        val mnemonic = MnemonicCreator.fromEntropy(entropy).words

        val tronChainSecrets = accountSecretsFactory.chainAccountSecrets(
            derivationPath = TRON_DEFAULT_DERIVATION_PATH,
            accountSource = AccountSecretsFactory.AccountSource.Mnemonic(substrateCryptoType, mnemonic),
            isEthereum = true
        ).secrets

        val tronKeypair = mapKeypairStructToKeypair(tronChainSecrets[ChainAccountSecrets.Keypair])

        val updatedSecrets = MetaAccountSecrets(
            substrateKeyPair = mapKeypairStructToKeypair(secrets.substrateKeypair),
            entropy = secrets.entropy,
            substrateSeed = secrets.seed,
            substrateDerivationPath = secrets.substrateDerivationPath,
            ethereumKeypair = secrets.ethereumKeypair?.let(::mapKeypairStructToKeypair),
            ethereumDerivationPath = secrets.ethereumDerivationPath,
            tronKeypair = tronKeypair,
            tronDerivationPath = TRON_DEFAULT_DERIVATION_PATH
        )

        secretStoreV2.putMetaAccountSecrets(account.id, updatedSecrets)

        val tronAccountId = tronKeypair.publicKey.tronPublicKeyToAccountId()
        metaAccountDao.updateMetaAccount(account.id) { it.addTronAccount(tronKeypair.publicKey, tronAccountId) }
    }
}
