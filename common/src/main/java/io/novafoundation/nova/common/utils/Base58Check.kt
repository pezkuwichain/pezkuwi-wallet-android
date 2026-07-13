package io.novafoundation.nova.common.utils

import java.math.BigInteger

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/**
 * Base58Check - the legacy encoding used by Bitcoin's P2PKH (`1...`) and P2SH (`3...`) addresses, as opposed to
 * native SegWit's bech32. Needed only to recognize/decode EXTERNAL destination addresses for sending (many
 * exchanges, including at least one confirmed live, only offer a P2SH withdrawal address with no way to pick a
 * native SegWit one) - this app's OWN address (derivation, receiving, `bitcoinAddressToAccountId()`) remains
 * native SegWit only, unaffected by this.
 */
object Base58Check {

    class Decoded(val version: Int, val payload: ByteArray)

    fun decode(address: String): Decoded {
        val full = base58Decode(address)
        require(full.size == 25) { "Base58Check payload must be 25 bytes, got ${full.size}" }

        val versionAndPayload = full.copyOfRange(0, 21)
        val checksum = full.copyOfRange(21, 25)
        val expectedChecksum = versionAndPayload.sha256d().copyOfRange(0, 4)

        require(checksum.contentEquals(expectedChecksum)) { "Base58Check checksum mismatch" }

        return Decoded(version = versionAndPayload[0].toInt() and 0xff, payload = versionAndPayload.copyOfRange(1, 21))
    }

    private fun base58Decode(input: String): ByteArray {
        require(input.isNotEmpty()) { "Base58 input must not be empty" }

        var value = BigInteger.ZERO
        val base = BigInteger.valueOf(58)

        for (c in input) {
            val digit = BASE58_ALPHABET.indexOf(c)
            require(digit >= 0) { "Invalid base58 character: $c" }
            value = value.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }

        val bytes = value.toByteArray().let {
            // BigInteger.toByteArray() may prepend a 0x00 sign-disambiguation byte - strip it, it is not part of the data.
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }

        // Each leading '1' character encodes a leading zero byte that BigInteger's conversion would otherwise drop.
        val leadingZeros = input.takeWhile { it == '1' }.length

        return ByteArray(leadingZeros) + bytes
    }
}
