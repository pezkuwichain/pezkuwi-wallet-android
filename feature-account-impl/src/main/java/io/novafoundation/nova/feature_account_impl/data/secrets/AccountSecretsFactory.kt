package io.novafoundation.nova.feature_account_impl.data.secrets

import io.novafoundation.nova.common.data.mappers.mapCryptoTypeToEncryption
import io.novafoundation.nova.common.data.mappers.mapEncryptionToCryptoType
import io.novafoundation.nova.common.data.network.runtime.binding.cast
import io.novafoundation.nova.common.data.secrets.v2.ChainAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.MetaAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.mapKeypairStructToKeypair
import io.novafoundation.nova.common.utils.castOrNull
import io.novafoundation.nova.common.utils.deriveSeed32
import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.feature_account_api.data.derivationPath.DerivationPathDecoder
import io.novasama.substrate_sdk_android.encrypt.EncryptionType
import io.novasama.substrate_sdk_android.encrypt.MultiChainEncryption
import io.novasama.substrate_sdk_android.encrypt.json.JsonDecoder
import io.novasama.substrate_sdk_android.encrypt.junction.JunctionDecoder
import io.novasama.substrate_sdk_android.encrypt.keypair.bip32.Bip32EcdsaKeypairFactory
import io.novasama.substrate_sdk_android.encrypt.keypair.generate
import io.novasama.substrate_sdk_android.encrypt.keypair.substrate.Sr25519SubstrateKeypairFactory
import io.novasama.substrate_sdk_android.encrypt.keypair.substrate.SubstrateKeypairFactory
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import io.novasama.substrate_sdk_android.encrypt.seed.SeedFactory
import io.novasama.substrate_sdk_android.encrypt.seed.bip39.Bip39SeedFactory
import io.novasama.substrate_sdk_android.encrypt.seed.substrate.SubstrateSeedFactory
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.scale.EncodableStruct
import io.novasama.substrate_sdk_android.scale.Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SLIP-44 coin type 195 is Tron's registered BIP44 coin type (Ethereum's is 60).
 * Not user-configurable via the "Advanced Encryption" UI yet (Phase 1 read-only Tron support) - always derived at this fixed path.
 */
const val TRON_DEFAULT_DERIVATION_PATH = "//44//195//0/0/0"

/**
 * BIP84 purpose (native SegWit), SLIP-44 coin type 0 (Bitcoin). Deliberately `//84//...`, not `//44//...` -
 * this app only supports native SegWit (bech32 `bc1q...`) addresses, not legacy/P2SH-SegWit, so the derivation
 * path signals that choice the same way real Bitcoin wallets do. Not user-configurable yet - always derived at
 * this fixed path (single address, no HD address-index rotation).
 */
const val BITCOIN_DEFAULT_DERIVATION_PATH = "//84//0//0/0/0"

class AccountSecretsFactory(
    private val JsonDecoder: JsonDecoder
) {

    sealed class AccountSource {

        class Mnemonic(val cryptoType: CryptoType, val mnemonic: String) : AccountSource()

        class Seed(val cryptoType: CryptoType, val seed: String) : AccountSource()

        class Json(val json: String, val password: String) : AccountSource()

        class EncodedSr25519Keypair(val key: ByteArray) : AccountSource()
    }

    sealed class SecretsError : Exception() {

        class NotValidEthereumCryptoType : SecretsError()

        class NotValidSubstrateCryptoType : SecretsError()
    }

    data class Result<S : Schema<S>>(val secrets: EncodableStruct<S>, val cryptoType: CryptoType)

    suspend fun chainAccountSecrets(
        derivationPath: String?,
        accountSource: AccountSource,
        isEthereum: Boolean,
    ): Result<ChainAccountSecrets> = withContext(Dispatchers.Default) {
        val mnemonicWords = accountSource.castOrNull<AccountSource.Mnemonic>()?.mnemonic
        val entropy = mnemonicWords?.let(MnemonicCreator::fromWords)?.entropy
        val decodedDerivationPath = decodeDerivationPath(derivationPath, ethereum = isEthereum)

        val decodedJson = accountSource.castOrNull<AccountSource.Json>()?.let { jsonSource ->
            JsonDecoder.decode(jsonSource.json, jsonSource.password).also {
                // only allow Ethereum JSONs for ethereum chains
                if (isEthereum && it.multiChainEncryption != MultiChainEncryption.Ethereum) {
                    throw SecretsError.NotValidEthereumCryptoType()
                }

                // only allow Substrate JSONs for substrate chains
                if (!isEthereum && it.multiChainEncryption == MultiChainEncryption.Ethereum) {
                    throw SecretsError.NotValidSubstrateCryptoType()
                }
            }
        }

        val encryptionType = when (accountSource) {
            is AccountSource.Mnemonic -> mapCryptoTypeToEncryption(accountSource.cryptoType)
            is AccountSource.Seed -> mapCryptoTypeToEncryption(accountSource.cryptoType)
            is AccountSource.Json -> decodedJson!!.multiChainEncryption.encryptionType
            is AccountSource.EncodedSr25519Keypair -> EncryptionType.SR25519
        }

        val seed = when (accountSource) {
            is AccountSource.Mnemonic -> deriveSeed(accountSource.mnemonic, decodedDerivationPath?.password, ethereum = isEthereum).seed
            is AccountSource.Seed -> accountSource.seed.fromHex()
            is AccountSource.Json -> null
            is AccountSource.EncodedSr25519Keypair -> null
        }

        val keypair = when {
            seed != null -> {
                val junctions = decodedDerivationPath?.junctions.orEmpty()

                if (isEthereum) {
                    Bip32EcdsaKeypairFactory.generate(seed, junctions)
                } else {
                    SubstrateKeypairFactory.generate(encryptionType, seed, junctions)
                }
            }

            decodedJson != null -> {
                decodedJson.keypair
            }

            else -> {
                val encodedSr25519Keypair = accountSource.cast<AccountSource.EncodedSr25519Keypair>()
                Sr25519SubstrateKeypairFactory.createKeypairFromSecret(encodedSr25519Keypair.key)
            }
        }

        val secrets = ChainAccountSecrets(
            keyPair = keypair,
            entropy = entropy,
            seed = seed,
            derivationPath = derivationPath,
        )

        Result(secrets = secrets, cryptoType = mapEncryptionToCryptoType(encryptionType))
    }

    suspend fun metaAccountSecrets(
        substrateDerivationPath: String?,
        ethereumDerivationPath: String?,
        accountSource: AccountSource,
        tronDerivationPath: String? = TRON_DEFAULT_DERIVATION_PATH,
        bitcoinDerivationPath: String? = BITCOIN_DEFAULT_DERIVATION_PATH,
    ): Result<MetaAccountSecrets> = withContext(Dispatchers.Default) {
        val (substrateSecrets, substrateCryptoType) = chainAccountSecrets(
            derivationPath = substrateDerivationPath,
            accountSource = accountSource,
            isEthereum = false
        )

        val ethereumKeypair = accountSource.castOrNull<AccountSource.Mnemonic>()?.let {
            val decodedEthereumDerivationPath = decodeDerivationPath(ethereumDerivationPath, ethereum = true)

            val seed = deriveSeed(it.mnemonic, password = decodedEthereumDerivationPath?.password, ethereum = true).seed

            Bip32EcdsaKeypairFactory.generate(seed = seed, junctions = decodedEthereumDerivationPath?.junctions.orEmpty())
        }

        // Tron uses the same secp256k1/BIP32 keypair generation as Ethereum, just under its own SLIP-44 coin-type (195)
        // derivation path, so it always yields a different keypair from the Ethereum one above, even though the math is identical.
        val tronKeypair = accountSource.castOrNull<AccountSource.Mnemonic>()?.let {
            // "Ethereum" here just means "BIP32/ECDSA junction decoding", which is exactly what Tron's path also needs.
            val decodedTronDerivationPath = decodeDerivationPath(tronDerivationPath, ethereum = true)

            val seed = deriveSeed(it.mnemonic, password = decodedTronDerivationPath?.password, ethereum = true).seed

            Bip32EcdsaKeypairFactory.generate(seed = seed, junctions = decodedTronDerivationPath?.junctions.orEmpty())
        }

        // Bitcoin (native SegWit) also reuses the exact same secp256k1/BIP32 keypair generation as Ethereum/Tron -
        // only the SLIP-44 coin type (0) and BIP84 purpose differ. See BITCOIN_DEFAULT_DERIVATION_PATH's doc.
        val bitcoinKeypair = accountSource.castOrNull<AccountSource.Mnemonic>()?.let {
            val decodedBitcoinDerivationPath = decodeDerivationPath(bitcoinDerivationPath, ethereum = true)

            val seed = deriveSeed(it.mnemonic, password = decodedBitcoinDerivationPath?.password, ethereum = true).seed

            Bip32EcdsaKeypairFactory.generate(seed = seed, junctions = decodedBitcoinDerivationPath?.junctions.orEmpty())
        }

        val secrets = MetaAccountSecrets(
            entropy = substrateSecrets[ChainAccountSecrets.Entropy],
            substrateSeed = substrateSecrets[ChainAccountSecrets.Seed],
            substrateKeyPair = mapKeypairStructToKeypair(substrateSecrets[ChainAccountSecrets.Keypair]),
            substrateDerivationPath = substrateDerivationPath,
            ethereumKeypair = ethereumKeypair,
            ethereumDerivationPath = ethereumDerivationPath,
            tronKeypair = tronKeypair,
            tronDerivationPath = tronDerivationPath,
            bitcoinKeypair = bitcoinKeypair,
            bitcoinDerivationPath = bitcoinDerivationPath,
        )

        Result(secrets = secrets, cryptoType = substrateCryptoType)
    }

    private fun deriveSeed(mnemonic: String, password: String?, ethereum: Boolean): SeedFactory.Result {
        return if (ethereum) {
            Bip39SeedFactory.deriveSeed(mnemonic, password)
        } else {
            SubstrateSeedFactory.deriveSeed32(mnemonic, password)
        }
    }

    private fun decodeDerivationPath(derivationPath: String?, ethereum: Boolean): JunctionDecoder.DecodeResult? {
        return when {
            ethereum -> DerivationPathDecoder.decodeEthereumDerivationPath(derivationPath)
            else -> DerivationPathDecoder.decodeSubstrateDerivationPath(derivationPath)
        }
    }
}
