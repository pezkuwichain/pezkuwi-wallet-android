package io.novafoundation.nova.common.utils

private const val P2PKH_VERSION = 0x00
private const val P2SH_VERSION = 0x05

/**
 * Any Bitcoin address this wallet can SEND to - a strict superset of what it can derive/receive as its own
 * address (native SegWit only, see `BitcoinAddress.kt`). Needed because a real exchange withdrawal address was
 * confirmed live to be P2SH (`3...`), which this app's original native-SegWit-only `isValidBitcoinAddress()`
 * rejected outright, blocking the send with a misleading "QR can't be decoded" error (the QR was fine - a bare
 * P2SH address - the address TYPE just wasn't recognized as a valid destination at all).
 *
 * Deliberately kept separate from [bitcoinAddressToAccountId]/[AccountId] - that function's 20-byte output feeds
 * generic multi-chain code (`Chain.accountIdOf`) that assumes every account id is THIS wallet's own P2WPKH
 * shape. Silently reusing it for a P2SH/P2PKH recipient would produce a 20-byte hash with no type tag, and
 * downstream code would wrap it in the wrong (P2WPKH) scriptPubKey - sending funds to an address that doesn't
 * match what was actually asked for. [BitcoinDestination] carries its type through to [toScriptPubKey] instead.
 */
sealed class BitcoinDestination {

    data class NativeSegwit(val witnessProgram: ByteArray) : BitcoinDestination()

    data class P2sh(val scriptHash: ByteArray) : BitcoinDestination()

    data class P2pkh(val pubKeyHash: ByteArray) : BitcoinDestination()
}

fun String.decodeBitcoinDestination(): BitcoinDestination {
    runCatching {
        val decoded = SegwitAddress.decode("bc", this)
        if (decoded.witnessVersion == 0 && decoded.witnessProgram.size == 20) {
            return BitcoinDestination.NativeSegwit(decoded.witnessProgram)
        }
    }

    // Base58Check itself is chain-agnostic (see TronAddress.kt) - a Bitcoin legacy address is 1 version byte +
    // 20-byte hash, same shape as Tron's own address, just a different version byte and no fixed prefix meaning.
    val decoded = Base58Check.decode(this)
    require(decoded.size == 21) { "Not a valid Bitcoin legacy address: $this" }

    val version = decoded[0].toInt() and 0xff
    val hash = decoded.copyOfRange(1, decoded.size)

    return when (version) {
        P2PKH_VERSION -> BitcoinDestination.P2pkh(hash)
        P2SH_VERSION -> BitcoinDestination.P2sh(hash)
        else -> error("Unsupported Bitcoin address version byte: $version")
    }
}

fun String.isValidBitcoinDestinationAddress(): Boolean = runCatching { decodeBitcoinDestination() }.isSuccess

/**
 * The raw 20-byte hash underlying any destination type, with its type tag dropped - safe ONLY for consumers
 * that treat it as an opaque identicon/display seed (e.g. `Chain.accountIdOf` and, downstream, address icon
 * generation) and never feed it back into [toScriptPubKey] or re-encode it as an address. Real transaction
 * construction must keep using [decodeBitcoinDestination]/[toScriptPubKey] directly, which keep the type.
 */
val BitcoinDestination.hash: ByteArray
    get() = when (this) {
        is BitcoinDestination.NativeSegwit -> witnessProgram
        is BitcoinDestination.P2sh -> scriptHash
        is BitcoinDestination.P2pkh -> pubKeyHash
    }

/**
 * @return the scriptPubKey a transaction output must use to actually pay this destination - P2SH/P2PKH have
 * different script shapes from this wallet's own P2WPKH (see [toP2wpkhScriptPubKey]), despite all three being a
 * "20-byte hash wrapped in a short script."
 */
fun BitcoinDestination.toScriptPubKey(): ByteArray = when (this) {
    is BitcoinDestination.NativeSegwit -> byteArrayOf(0x00, 0x14) + witnessProgram

    // OP_HASH160 <20-byte-push> OP_EQUAL
    is BitcoinDestination.P2sh -> byteArrayOf(0xa9.toByte(), 0x14) + scriptHash + byteArrayOf(0x87.toByte())

    // OP_DUP OP_HASH160 <20-byte-push> OP_EQUALVERIFY OP_CHECKSIG
    is BitcoinDestination.P2pkh -> byteArrayOf(0x76, 0xa9.toByte(), 0x14) + pubKeyHash + byteArrayOf(0x88.toByte(), 0xac.toByte())
}
