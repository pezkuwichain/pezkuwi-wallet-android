package io.novafoundation.nova.common.data.secrets.v2

import io.novafoundation.nova.common.utils.invoke
import io.novasama.substrate_sdk_android.encrypt.keypair.Keypair
import io.novasama.substrate_sdk_android.encrypt.keypair.substrate.Sr25519Keypair
import io.novasama.substrate_sdk_android.scale.EncodableStruct
import io.novasama.substrate_sdk_android.scale.Schema
import io.novasama.substrate_sdk_android.scale.byteArray
import io.novasama.substrate_sdk_android.scale.schema
import io.novasama.substrate_sdk_android.scale.string

object KeyPairSchema : Schema<KeyPairSchema>() {
    val PrivateKey by byteArray()
    val PublicKey by byteArray()

    val Nonce by byteArray().optional()
}

object MetaAccountSecrets : Schema<MetaAccountSecrets>() {
    val Entropy by byteArray().optional()
    val SubstrateSeed by byteArray().optional()

    val SubstrateKeypair by schema(KeyPairSchema)
    val SubstrateDerivationPath by string().optional()

    val EthereumKeypair by schema(KeyPairSchema).optional()
    val EthereumDerivationPath by string().optional()

    val TronKeypair by schema(KeyPairSchema).optional()
    val TronDerivationPath by string().optional()

    val BitcoinKeypair by schema(KeyPairSchema).optional()
    val BitcoinDerivationPath by string().optional()

    val SolanaKeypair by schema(KeyPairSchema).optional()
    val SolanaDerivationPath by string().optional()
}

object ChainAccountSecrets : Schema<ChainAccountSecrets>() {
    val Entropy by byteArray().optional()
    val Seed by byteArray().optional()

    val Keypair by schema(KeyPairSchema)
    val DerivationPath by string().optional()
}

fun MetaAccountSecrets(
    substrateKeyPair: Keypair,
    entropy: ByteArray? = null,
    substrateSeed: ByteArray? = null,
    substrateDerivationPath: String? = null,
    ethereumKeypair: Keypair? = null,
    ethereumDerivationPath: String? = null,
    tronKeypair: Keypair? = null,
    tronDerivationPath: String? = null,
    bitcoinKeypair: Keypair? = null,
    bitcoinDerivationPath: String? = null,
    solanaKeypair: Keypair? = null,
    solanaDerivationPath: String? = null,
): EncodableStruct<MetaAccountSecrets> = MetaAccountSecrets { secrets ->
    secrets[Entropy] = entropy
    secrets[SubstrateSeed] = substrateSeed

    secrets[SubstrateKeypair] = KeyPairSchema { keypair ->
        keypair[PublicKey] = substrateKeyPair.publicKey
        keypair[PrivateKey] = substrateKeyPair.privateKey
        keypair[Nonce] = (substrateKeyPair as? Sr25519Keypair)?.nonce
    }
    secrets[SubstrateDerivationPath] = substrateDerivationPath

    secrets[EthereumKeypair] = ethereumKeypair?.let {
        KeyPairSchema { keypair ->
            keypair[PublicKey] = it.publicKey
            keypair[PrivateKey] = it.privateKey
            keypair[Nonce] = null // ethereum does not support Sr25519 so nonce is always null
        }
    }
    secrets[EthereumDerivationPath] = ethereumDerivationPath

    secrets[TronKeypair] = tronKeypair?.let {
        KeyPairSchema { keypair ->
            keypair[PublicKey] = it.publicKey
            keypair[PrivateKey] = it.privateKey
            keypair[Nonce] = null // tron uses secp256k1 (like ethereum), so nonce is always null
        }
    }
    secrets[TronDerivationPath] = tronDerivationPath

    secrets[BitcoinKeypair] = bitcoinKeypair?.let {
        KeyPairSchema { keypair ->
            keypair[PublicKey] = it.publicKey
            keypair[PrivateKey] = it.privateKey
            keypair[Nonce] = null // bitcoin uses secp256k1 (like ethereum/tron), so nonce is always null
        }
    }
    secrets[BitcoinDerivationPath] = bitcoinDerivationPath

    secrets[SolanaKeypair] = solanaKeypair?.let {
        KeyPairSchema { keypair ->
            keypair[PublicKey] = it.publicKey
            keypair[PrivateKey] = it.privateKey
            keypair[Nonce] = null // Solana uses Ed25519, not Sr25519, so nonce is always null
        }
    }
    secrets[SolanaDerivationPath] = solanaDerivationPath
}

fun ChainAccountSecrets(
    keyPair: Keypair,
    entropy: ByteArray? = null,
    seed: ByteArray? = null,
    derivationPath: String? = null,
): EncodableStruct<ChainAccountSecrets> = ChainAccountSecrets { secrets ->
    secrets[Entropy] = entropy
    secrets[Seed] = seed

    secrets[Keypair] = KeyPairSchema { keypair ->
        keypair[PublicKey] = keyPair.publicKey
        keypair[PrivateKey] = keyPair.privateKey
        keypair[Nonce] = (keyPair as? Sr25519Keypair)?.nonce
    }
    secrets[DerivationPath] = derivationPath
}

val EncodableStruct<MetaAccountSecrets>.substrateDerivationPath
    get() = get(MetaAccountSecrets.SubstrateDerivationPath)

val EncodableStruct<MetaAccountSecrets>.ethereumDerivationPath
    get() = get(MetaAccountSecrets.EthereumDerivationPath)

val EncodableStruct<MetaAccountSecrets>.tronDerivationPath
    get() = get(MetaAccountSecrets.TronDerivationPath)

val EncodableStruct<MetaAccountSecrets>.bitcoinDerivationPath
    get() = get(MetaAccountSecrets.BitcoinDerivationPath)

val EncodableStruct<MetaAccountSecrets>.solanaDerivationPath
    get() = get(MetaAccountSecrets.SolanaDerivationPath)

val EncodableStruct<MetaAccountSecrets>.entropy
    get() = get(MetaAccountSecrets.Entropy)

val EncodableStruct<MetaAccountSecrets>.seed
    get() = get(MetaAccountSecrets.SubstrateSeed)

val EncodableStruct<MetaAccountSecrets>.substrateKeypair
    get() = get(MetaAccountSecrets.SubstrateKeypair)

val EncodableStruct<MetaAccountSecrets>.ethereumKeypair
    get() = get(MetaAccountSecrets.EthereumKeypair)

val EncodableStruct<MetaAccountSecrets>.tronKeypair
    get() = get(MetaAccountSecrets.TronKeypair)

val EncodableStruct<MetaAccountSecrets>.bitcoinKeypair
    get() = get(MetaAccountSecrets.BitcoinKeypair)

val EncodableStruct<MetaAccountSecrets>.solanaKeypair
    get() = get(MetaAccountSecrets.SolanaKeypair)

val EncodableStruct<ChainAccountSecrets>.derivationPath
    get() = get(ChainAccountSecrets.DerivationPath)

@get:JvmName("chainAccountEntropy")
val EncodableStruct<ChainAccountSecrets>.entropy
    get() = get(ChainAccountSecrets.Entropy)

@get:JvmName("chainAccountSeed")
val EncodableStruct<ChainAccountSecrets>.seed
    get() = get(ChainAccountSecrets.Seed)

val EncodableStruct<ChainAccountSecrets>.keypair
    get() = get(ChainAccountSecrets.Keypair)
val EncodableStruct<KeyPairSchema>.privateKey
    get() = get(KeyPairSchema.PrivateKey)

val EncodableStruct<KeyPairSchema>.publicKey
    get() = get(KeyPairSchema.PublicKey)

val EncodableStruct<KeyPairSchema>.nonce
    get() = get(KeyPairSchema.Nonce)
