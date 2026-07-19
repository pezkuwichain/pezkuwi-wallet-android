package io.novafoundation.nova.feature_account_impl.data.repository.datasource.migration

import android.util.Log
import io.novafoundation.nova.common.data.secrets.v2.MetaAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.SecretStoreV2
import io.novafoundation.nova.common.data.secrets.v2.bitcoinDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.bitcoinKeypair
import io.novafoundation.nova.common.data.secrets.v2.entropy
import io.novafoundation.nova.common.data.secrets.v2.ethereumDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.ethereumKeypair
import io.novafoundation.nova.common.data.secrets.v2.mapKeypairStructToKeypair
import io.novafoundation.nova.common.data.secrets.v2.seed
import io.novafoundation.nova.common.data.secrets.v2.solanaKeypair
import io.novafoundation.nova.common.data.secrets.v2.substrateDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.substrateKeypair
import io.novafoundation.nova.common.data.secrets.v2.tronDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.tronKeypair
import io.novafoundation.nova.common.utils.Bip32Ed25519KeypairFactory
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.dao.updateMetaAccount
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novafoundation.nova.feature_account_impl.data.secrets.SOLANA_DEFAULT_DERIVATION_PATH
import io.novafoundation.nova.feature_account_impl.data.secrets.SOLANA_DEFAULT_DERIVATION_PATH_SEGMENTS
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import io.novasama.substrate_sdk_android.encrypt.seed.bip39.Bip39SeedFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SolanaAddressBackfill"

/**
 * Idempotent, per-account backfill for accounts that don't yet have a Solana keypair - mirrors
 * [TronAddressBackfillMigration]/[BitcoinAddressBackfillMigration]'s exact design (see those classes for the
 * full rationale). Deliberately has NO "have I already run once" flag, for the same stuck-flag reason.
 *
 * Only touches accounts that are `Type.SECRETS` and still hold their `Entropy` in [SecretStoreV2] - same
 * restriction as the Tron/Bitcoin migrations.
 *
 * Unlike Tron/Bitcoin (which reuse [io.novafoundation.nova.feature_account_impl.data.secrets.AccountSecretsFactory.chainAccountSecrets]
 * with `isEthereum = true`, since both are secp256k1/BIP32), Solana is Ed25519/SLIP-0010 - a different curve and
 * derivation scheme entirely - so this derives the seed directly via [Bip39SeedFactory.deriveSeed] (the exact
 * same chain-agnostic BIP39 seed step Tron/Bitcoin/Ethereum all use) and feeds it to [Bip32Ed25519KeypairFactory]
 * at [SOLANA_DEFAULT_DERIVATION_PATH_SEGMENTS], the same call `AccountSecretsFactory.metaAccountSecrets()` makes
 * for a fresh account - so a backfilled account ends up with byte-for-byte the same Solana address it would
 * have gotten had it been created today.
 *
 * Carries every other chain-family field (substrate/ethereum/tron/bitcoin) forward unchanged when rewriting
 * secrets - dropping any of them here would silently wipe an already-backfilled sibling key on retry.
 */
class SolanaAddressBackfillMigration(
    private val secretStoreV2: SecretStoreV2,
    private val metaAccountDao: MetaAccountDao,
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
        if (secrets.solanaKeypair != null) {
            Log.d(TAG, "metaId=${account.id}: already has a SolanaKeypair - skipping")
            return
        }

        Log.d(TAG, "metaId=${account.id}: deriving Solana keypair")

        val mnemonic = MnemonicCreator.fromEntropy(entropy).words
        val bip39Seed = Bip39SeedFactory.deriveSeed(mnemonic, null).seed

        val solanaKeypair = Bip32Ed25519KeypairFactory.generate(bip39Seed, SOLANA_DEFAULT_DERIVATION_PATH_SEGMENTS)

        val updatedSecrets = MetaAccountSecrets(
            substrateKeyPair = mapKeypairStructToKeypair(secrets.substrateKeypair),
            entropy = secrets.entropy,
            substrateSeed = secrets.seed,
            substrateDerivationPath = secrets.substrateDerivationPath,
            ethereumKeypair = secrets.ethereumKeypair?.let(::mapKeypairStructToKeypair),
            ethereumDerivationPath = secrets.ethereumDerivationPath,
            tronKeypair = secrets.tronKeypair?.let(::mapKeypairStructToKeypair),
            tronDerivationPath = secrets.tronDerivationPath,
            bitcoinKeypair = secrets.bitcoinKeypair?.let(::mapKeypairStructToKeypair),
            bitcoinDerivationPath = secrets.bitcoinDerivationPath,
            solanaKeypair = solanaKeypair,
            solanaDerivationPath = SOLANA_DEFAULT_DERIVATION_PATH
        )

        secretStoreV2.putMetaAccountSecrets(account.id, updatedSecrets)

        // Solana's accountId IS the public key itself - see SolanaAddress.kt's doc.
        metaAccountDao.updateMetaAccount(account.id) { it.addSolanaAccount(solanaKeypair.publicKey, solanaKeypair.publicKey) }

        Log.d(TAG, "metaId=${account.id}: backfilled successfully, solanaAddress set (${solanaKeypair.publicKey.size} bytes)")
    }
}
