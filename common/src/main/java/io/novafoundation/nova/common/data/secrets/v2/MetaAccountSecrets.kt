package io.novafoundation.nova.common.data.secrets.v2

import io.emeraldpay.polkaj.scale.ScaleCodecReader
import io.novafoundation.nova.common.utils.invoke
import io.novasama.substrate_sdk_android.encrypt.keypair.Keypair
import io.novasama.substrate_sdk_android.encrypt.keypair.substrate.Sr25519Keypair
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.scale.EncodableStruct
import io.novasama.substrate_sdk_android.scale.Field
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

/**
 * `MetaAccountSecrets.read(hex)` (`Schema.read`'in kendisi) bir stored blob'u bu şemadaki TÜM alanları sırayla
 * okuyarak parse eder - herhangi bir alan arabellekte hiç yoksa (o alan eklenmeden ÖNCE yazılmış eski bir
 * hesapsa) `optional<T>.read()` zarifçe null dönmek yerine çöker (`reader.readBoolean()` EOF'ta istisna
 * fırlatıyor, kütüphanede hiç yakalama yok). Bu, gerçek cihazda backup ekranı açılırken doğrulandı: bu hesabın
 * verisi Solana alanları eklenmeden önce yazılmıştı, okuma "Cannot read 412 of 412" ile çöktü - ve Solana
 * migration'ının KENDİSİ de her çalışmada aynı çökmeyi kendi try/catch'inde sessizce yutuyordu (solanaAddress'in
 * hiç yazılamamasının, yani SOL'ün hiç görünmemesinin gerçek nedeni buydu). Tron/Bitcoin eklenirken de birebir
 * aynı riskli desen kullanılmıştı - muhtemelen şans eseri (eski blob'larda tesadüfi byte hizalaması) hiç yüzeye
 * çıkmamıştı.
 *
 * Denenen ilk düzeltme (alan bazında özel bir DataType sarmalayıcısı) YANLIŞ çıktı: `EncodableStruct.get()`
 * (kütüphanenin kendi `ScaleStruct.kt`'si) bir alan null olduğunda sadece `field.dataType is optional<*>` ise
 * null döner - `optional`'ın kendisi `final` bir sınıf, alt sınıflanamaz, ve genel bir `DataType` sarmalayıcısı
 * bu kontrolden geçemeyip AYNI çökmeyi okuma yerine erişim anında tekrar üretiyordu (gerçek CI testleri bunu
 * yakaladı).
 *
 * Doğru çözüm: şemayı (yukarıda) TAMAMEN orijinal haliyle bırak - `optional<T>` her zaman kütüphanenin kendi
 * sınıfı olsun ki `get()` doğru çalışsın - ve okumayı kendi elimizle, alan alan, arabelleğin nerede tükendiğini
 * yakalayarak yap. `EncodableStruct.set()`'i hiç ÇAĞIRMAMAK (bir alanı okumaya çalışmamak) `get()`'in zaten
 * doğru olan "değer yok + optional -> null" davranışını tetikler - kütüphaneyi değil, kendi okuma sırasını
 * genişletiyoruz.
 */
fun readMetaAccountSecrets(hex: String): EncodableStruct<MetaAccountSecrets> {
    return try {
        MetaAccountSecrets.read(hex)
    } catch (e: Exception) {
        readMetaAccountSecretsTolerant(hex)
    }
}

private fun readMetaAccountSecretsTolerant(hex: String): EncodableStruct<MetaAccountSecrets> {
    val reader = ScaleCodecReader(hex.fromHex())
    val struct = EncodableStruct(MetaAccountSecrets)

    // Once one field's bytes don't exist, the reader's position is meaningless for everything after it too -
    // every field from that point on must be left unset (not attempted), same as it being absent gets treated
    // by EncodableStruct.get() for every already-real `.optional()` field above.
    var truncated = false

    fun <T> trySet(field: Field<T>) {
        if (truncated) return

        try {
            struct[field] = field.dataType.read(reader)
        } catch (e: Exception) {
            truncated = true
        }
    }

    trySet(MetaAccountSecrets.Entropy)
    trySet(MetaAccountSecrets.SubstrateSeed)
    trySet(MetaAccountSecrets.SubstrateKeypair)
    trySet(MetaAccountSecrets.SubstrateDerivationPath)
    trySet(MetaAccountSecrets.EthereumKeypair)
    trySet(MetaAccountSecrets.EthereumDerivationPath)
    trySet(MetaAccountSecrets.TronKeypair)
    trySet(MetaAccountSecrets.TronDerivationPath)
    trySet(MetaAccountSecrets.BitcoinKeypair)
    trySet(MetaAccountSecrets.BitcoinDerivationPath)
    trySet(MetaAccountSecrets.SolanaKeypair)
    trySet(MetaAccountSecrets.SolanaDerivationPath)

    return struct
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
