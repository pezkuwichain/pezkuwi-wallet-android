package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.encrypt.keypair.BaseKeypair
import io.novasama.substrate_sdk_android.encrypt.keypair.Keypair
import io.novasama.substrate_sdk_android.runtime.AccountId
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Solana address format: the raw 32-byte Ed25519 public key, Base58-encoded directly - no
 * checksum, no prefix byte (unlike Tron's Base58Check, see [TronAddress.kt]'s [Base58Check]).
 * [Base58] (defined there) is reused as-is since it's exactly this: a plain, non-checksummed
 * Base58 codec.
 *
 * Solana's account id IS the public key itself (no separate keccak/hash-based derivation the way
 * Ethereum/Tron/Bitcoin have) - `AccountId` here is always exactly 32 bytes.
 *
 * Derivation: SLIP-0010 (Ed25519 hierarchical, hardened-only - Ed25519 has no public-key-only
 * derivation the way secp256k1/BIP32 does, so every path segment is implicitly hardened) at path
 * m/44'/501'/0'/0' (SLIP-44 coin type 501, the modern Phantom/Solflare/Solana CLI default - NOT
 * the older Sollet-style m/44'/501'/0' with no final change level). Cross-validated against an
 * independent implementation (the `slip10` PyPI package) for the standard BIP39 test mnemonic
 * ("abandon x11 about", empty passphrase): both agree on
 * HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk - see [SolanaAddressTest].
 */
private const val SOLANA_SEED_HMAC_KEY = "ed25519 seed"

object Bip32Ed25519KeypairFactory {

    /**
     * `seed` is the standard 64-byte BIP39 seed (the exact same one Ethereum/Tron/Bitcoin derive
     * via `Bip39SeedFactory.deriveSeed` - BIP39 seed generation itself is chain-agnostic, only
     * what happens to it afterward differs). `path` is a list of child indices - all implicitly
     * hardened, so callers pass plain ints (44, 501, 0, 0), not pre-marked/offset values.
     */
    fun generate(seed: ByteArray, path: List<Int>): Keypair {
        var (key, chainCode) = masterKeyFromSeed(seed)

        for (index in path) {
            val (childKey, childChainCode) = deriveHardenedChild(key, chainCode, index)
            key = childKey
            chainCode = childChainCode
        }

        val privateKeyParams = Ed25519PrivateKeyParameters(key, 0)
        val publicKey = privateKeyParams.generatePublicKey().encoded

        return BaseKeypair(privateKey = key, publicKey = publicKey)
    }

    private fun masterKeyFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val digest = hmacSha512(SOLANA_SEED_HMAC_KEY.toByteArray(Charsets.UTF_8), seed)
        return digest.copyOfRange(0, 32) to digest.copyOfRange(32, 64)
    }

    /** SLIP-0010's ed25519 child derivation is always hardened: `HMAC-SHA512(chainCode, 0x00 ++
     *  parentKey ++ ser32(index | 0x80000000))`, unlike BIP32 secp256k1 (which can also derive
     *  non-hardened children from a public key alone) - there is no non-hardened case to handle. */
    private fun deriveHardenedChild(key: ByteArray, chainCode: ByteArray, index: Int): Pair<ByteArray, ByteArray> {
        val hardenedIndex = index or (1 shl 31)
        val data = byteArrayOf(0) + key + ser32(hardenedIndex)

        val digest = hmacSha512(chainCode, data)
        return digest.copyOfRange(0, 32) to digest.copyOfRange(32, 64)
    }

    private fun ser32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
}

fun AccountId.toSolanaAddress(): String {
    require(size == 32) { "Solana account id (public key) must be 32 bytes, got $size" }

    return Base58.encode(this)
}

fun String.solanaAddressToAccountId(): AccountId {
    val decoded = Base58.decode(this)
    require(decoded.size == 32) { "Not a valid Solana address: $this" }

    return decoded
}

fun String.isValidSolanaAddress(): Boolean = runCatching { solanaAddressToAccountId() }.isSuccess

fun emptySolanaAccountId() = ByteArray(32) { 1 }
