package io.novafoundation.nova.feature_account_impl.data.repository.datasource.migration

import android.util.Log
import io.novafoundation.nova.common.data.secrets.v2.ChainAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.MetaAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.SecretStoreV2
import io.novafoundation.nova.common.data.secrets.v2.bitcoinDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.bitcoinKeypair
import io.novafoundation.nova.common.data.secrets.v2.entropy
import io.novafoundation.nova.common.data.secrets.v2.ethereumDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.ethereumKeypair
import io.novafoundation.nova.common.data.secrets.v2.mapKeypairStructToKeypair
import io.novafoundation.nova.common.data.secrets.v2.seed
import io.novafoundation.nova.common.data.secrets.v2.substrateDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.substrateKeypair
import io.novafoundation.nova.common.data.secrets.v2.tronDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.tronKeypair
import io.novafoundation.nova.common.utils.bitcoinPublicKeyToAccountId
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.dao.updateMetaAccount
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novafoundation.nova.feature_account_impl.data.secrets.AccountSecretsFactory
import io.novafoundation.nova.feature_account_impl.data.secrets.BITCOIN_DEFAULT_DERIVATION_PATH
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "BitcoinAddressBackfill"

/**
 * Idempotent, per-account backfill for accounts that don't yet have a Bitcoin keypair - mirrors
 * [TronAddressBackfillMigration]'s exact design (see that class for the full rationale): any account created
 * before Bitcoin support existed has no `MetaAccountSecrets.BitcoinKeypair`/`meta_accounts.bitcoinAddress` yet,
 * and this fills it in from the account's own mnemonic without requiring re-import.
 *
 * Deliberately has NO "have I already run once" flag, for the same reason as the Tron migration: a one-shot
 * flag that gets set even when backfill legitimately still didn't produce a key (e.g. a since-fixed bug kept
 * re-losing it) would permanently strand that account. This is cheap to call for an account that doesn't need
 * it, so it just runs unconditionally on every app start.
 *
 * Only touches accounts that are `Type.SECRETS` (mnemonic-derived) and still hold their `Entropy` in
 * [SecretStoreV2] - same restriction as the Tron migration, for the same reason (watch-only/Ledger/Json/
 * multisig/proxied accounts and raw-seed imports never had a Bitcoin-capable mnemonic to derive from).
 */
class BitcoinAddressBackfillMigration(
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
        if (secrets.bitcoinKeypair != null) {
            Log.d(TAG, "metaId=${account.id}: already has a BitcoinKeypair - skipping")
            return
        }
        val substrateCryptoType = account.substrateCryptoType
        if (substrateCryptoType == null) {
            Log.d(TAG, "metaId=${account.id}: substrateCryptoType is null - skipping")
            return
        }

        Log.d(TAG, "metaId=${account.id}: deriving Bitcoin keypair")

        val mnemonic = MnemonicCreator.fromEntropy(entropy).words

        val bitcoinChainSecrets = accountSecretsFactory.chainAccountSecrets(
            derivationPath = BITCOIN_DEFAULT_DERIVATION_PATH,
            accountSource = AccountSecretsFactory.AccountSource.Mnemonic(substrateCryptoType, mnemonic),
            isEthereum = true
        ).secrets

        val bitcoinKeypair = mapKeypairStructToKeypair(bitcoinChainSecrets[ChainAccountSecrets.Keypair])

        val updatedSecrets = MetaAccountSecrets(
            substrateKeyPair = mapKeypairStructToKeypair(secrets.substrateKeypair),
            entropy = secrets.entropy,
            substrateSeed = secrets.seed,
            substrateDerivationPath = secrets.substrateDerivationPath,
            ethereumKeypair = secrets.ethereumKeypair?.let(::mapKeypairStructToKeypair),
            ethereumDerivationPath = secrets.ethereumDerivationPath,
            tronKeypair = secrets.tronKeypair?.let(::mapKeypairStructToKeypair),
            tronDerivationPath = secrets.tronDerivationPath,
            bitcoinKeypair = bitcoinKeypair,
            bitcoinDerivationPath = BITCOIN_DEFAULT_DERIVATION_PATH
        )

        secretStoreV2.putMetaAccountSecrets(account.id, updatedSecrets)

        val bitcoinAccountId = bitcoinKeypair.publicKey.bitcoinPublicKeyToAccountId()
        metaAccountDao.updateMetaAccount(account.id) { it.addBitcoinAccount(bitcoinKeypair.publicKey, bitcoinAccountId) }

        Log.d(TAG, "metaId=${account.id}: backfilled successfully, bitcoinAddress set (${bitcoinAccountId.size} bytes)")
    }
}
