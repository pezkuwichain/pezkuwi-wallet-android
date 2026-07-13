package io.novafoundation.nova.feature_account_impl.data.repository.datasource.migration

import android.util.Log
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
import io.novafoundation.nova.common.utils.tronPublicKeyToAccountId
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.dao.updateMetaAccount
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novafoundation.nova.feature_account_impl.data.secrets.AccountSecretsFactory
import io.novafoundation.nova.feature_account_impl.data.secrets.TRON_DEFAULT_DERIVATION_PATH
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TronAddressBackfill"

/**
 * Idempotent, per-account backfill for accounts that don't yet have a Tron keypair - both accounts created
 * before Tron support existed, AND accounts whose Tron keypair was lost some other way (e.g. the cloud-backup
 * schema round trip that used to silently drop it before every wallet had a `tron` field to serialize into -
 * see CloudBackup.kt). `73_74_AddTronSupport` (the migration that added `meta_accounts.tronPublicKey`/
 * `tronAddress`) is, like every other migration in this codebase, pure `ALTER TABLE` - it never derives a value
 * for pre-existing rows.
 *
 * Deliberately has NO "have I already run once" flag: an earlier version of this class gated itself behind a
 * one-shot SharedPreferences flag, which meant that once it ran and marked itself done - even for an account
 * that legitimately still lacked a Tron keypair afterwards (e.g. because a *different*, since-fixed bug kept
 * re-losing it) - it would never run again for that account, ever, on that install. [backfillIfNeeded] is cheap
 * to call for an account that doesn't need it (a handful of null-checks, no derivation), so this just runs
 * unconditionally on every app start instead: self-healing by construction, no stuck-flag failure mode possible.
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
    private val secretStoreV2: SecretStoreV2,
    private val metaAccountDao: MetaAccountDao,
    private val accountSecretsFactory: AccountSecretsFactory,
) {

    suspend fun migrate() = withContext(Dispatchers.Default) {
        val secretsAccounts = metaAccountDao.getMetaAccounts().filter { it.type == MetaAccountLocal.Type.SECRETS }
        Log.d(TAG, "migrate() starting - ${secretsAccounts.size} SECRETS-type account(s): ${secretsAccounts.map { it.id }}")

        secretsAccounts.forEach { account ->
            try {
                backfillIfNeeded(account)
            } catch (e: Throwable) {
                Log.e(TAG, "backfill failed for metaId=${account.id}, continuing with remaining accounts", e)
            }
        }

        Log.d(TAG, "migrate() done")
    }

    private suspend fun backfillIfNeeded(account: MetaAccountLocal) {
        val secrets = secretStoreV2.getMetaAccountSecrets(account.id)
        if (secrets == null) {
            Log.d(TAG, "metaId=${account.id}: no stored secrets at all (watch-only/Ledger/etc) - skipping")
            return
        }
        val entropy = secrets.entropy
        if (entropy == null) {
            Log.d(TAG, "metaId=${account.id}: no entropy (raw-seed import, not a mnemonic) - skipping")
            return
        }
        if (secrets.tronKeypair != null) {
            Log.d(TAG, "metaId=${account.id}: already has a TronKeypair - skipping")
            return
        }
        val substrateCryptoType = account.substrateCryptoType
        if (substrateCryptoType == null) {
            Log.d(TAG, "metaId=${account.id}: substrateCryptoType is null - skipping")
            return
        }

        Log.d(TAG, "metaId=${account.id}: deriving Tron keypair")

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

        Log.d(TAG, "metaId=${account.id}: backfilled successfully, tronAddress set (${tronAccountId.size} bytes)")
    }
}
