package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.runtime.AccountId
import org.bouncycastle.jcajce.provider.digest.RIPEMD160

/**
 * Native SegWit (P2WPKH) Bitcoin address support. Only witness v0 P2WPKH (`bc1q...`) is implemented - Taproot
 * and legacy/P2SH-SegWit are explicitly out of scope for this phase (see the BTC integration plan).
 *
 * Bitcoin's "account id" here is the 20-byte HASH160 of the compressed secp256k1 public key - unlike
 * Tron/Ethereum (which both derive their account id via keccak256 of the *uncompressed* pubkey), so this is
 * NOT interchangeable with [tronPublicKeyToAccountId]/`asEthereumPublicKey().toAccountId()` despite all three
 * using the same underlying secp256k1 keypair machinery.
 */
private const val BITCOIN_MAINNET_HRP = "bc"

/** RIPEMD160(SHA256(x)) - the "HASH160" function used throughout Bitcoin for pubkey hashes and script hashes. */
fun ByteArray.hash160(): ByteArray {
    val ripemd160 = RIPEMD160.Digest()
    return ripemd160.digest(this.sha256())
}

/**
 * @param compressedPublicKey a 33-byte compressed secp256k1 public key (0x02/0x03 prefix + 32-byte x-coordinate).
 */
fun ByteArray.bitcoinPublicKeyToAccountId(): AccountId {
    require(size == 33) { "Bitcoin native SegWit requires a compressed (33-byte) public key, got $size bytes" }

    return hash160()
}

/** P2WPKH scriptPubKey: `OP_0 <20-byte-push> <hash160>`, i.e. `0x00 0x14 <20 bytes>`. */
fun AccountId.toP2wpkhScriptPubKey(): ByteArray {
    require(size == 20) { "Bitcoin account id (HASH160) must be 20 bytes, got $size" }

    return byteArrayOf(0x00, 0x14) + this
}

fun AccountId.toBitcoinAddress(): String {
    require(size == 20) { "Bitcoin account id (HASH160) must be 20 bytes, got $size" }

    return SegwitAddress.encode(BITCOIN_MAINNET_HRP, witnessVersion = 0, witnessProgram = this)
}

fun String.bitcoinAddressToAccountId(): AccountId {
    val decoded = SegwitAddress.decode(BITCOIN_MAINNET_HRP, this)
    require(decoded.witnessVersion == 0 && decoded.witnessProgram.size == 20) {
        "Not a native SegWit P2WPKH address: $this"
    }

    return decoded.witnessProgram
}

fun String.isValidBitcoinAddress(): Boolean = runCatching { bitcoinAddressToAccountId() }.isSuccess

fun emptyBitcoinAccountId() = ByteArray(20) { 1 }
