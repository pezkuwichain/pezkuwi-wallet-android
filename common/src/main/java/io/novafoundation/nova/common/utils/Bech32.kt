package io.novafoundation.nova.common.utils

/**
 * Bech32 (BIP173) / Bech32m (BIP350) codec, hand-implemented since no such library is currently on this
 * project's classpath (same rationale as [Base58]/[Base58Check] for Tron - this is a deterministic text
 * encoding, not a secret-dependent cryptographic primitive).
 *
 * Direct port of the reference algorithm in BIP173/BIP350's `segwit_addr.py`. Cross-checked against BIP173's
 * and BIP350's official test vectors - see [Bech32Test].
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1L
    private const val BECH32M_CONST = 0x2bc830a3L

    enum class Encoding(val const: Long) {
        BECH32(BECH32_CONST),
        BECH32M(BECH32M_CONST)
    }

    data class Decoded(val hrp: String, val values: IntArray, val encoding: Encoding)

    private fun polymod(values: IntArray): Long {
        val gen = longArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1L
        for (v in values) {
            val b = (chk ushr 25)
            chk = (chk and 0x1ffffff) shl 5 xor v.toLong()
            for (i in 0 until 5) {
                if ((b ushr i) and 1L == 1L) {
                    chk = chk xor gen[i]
                }
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val lower = hrp.map { (it.code ushr 5) }
        val upper = hrp.map { (it.code and 31) }
        return (lower + listOf(0) + upper).toIntArray()
    }

    private fun createChecksum(hrp: String, data: IntArray, encoding: Encoding): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor encoding.const
        return IntArray(6) { i -> ((mod ushr (5 * (5 - i))) and 31).toInt() }
    }

    fun encode(hrp: String, data: IntArray, encoding: Encoding): String {
        val checksum = createChecksum(hrp, data, encoding)
        val combined = data + checksum
        return hrp + "1" + combined.map { CHARSET[it] }.joinToString("")
    }

    fun decode(input: String): Decoded {
        require(input.length in 8..90) { "Bech32 string has invalid length: ${input.length}" }
        require(input == input.lowercase() || input == input.uppercase()) { "Bech32 string is mixed case: $input" }

        val lower = input.lowercase()
        val separatorIndex = lower.lastIndexOf('1')
        require(separatorIndex >= 1) { "Bech32 string is missing separator '1': $input" }
        require(separatorIndex + 7 <= lower.length) { "Bech32 data part too short: $input" }

        val hrp = lower.substring(0, separatorIndex)
        val dataPart = lower.substring(separatorIndex + 1)

        val values = IntArray(dataPart.length)
        for ((i, c) in dataPart.withIndex()) {
            val v = CHARSET.indexOf(c)
            require(v >= 0) { "Invalid Bech32 character: '$c' in $input" }
            values[i] = v
        }

        val checksumValue = polymod(hrpExpand(hrp) + values)
        val encoding = when (checksumValue) {
            BECH32_CONST -> Encoding.BECH32
            BECH32M_CONST -> Encoding.BECH32M
            else -> throw IllegalArgumentException("Invalid Bech32/Bech32m checksum: $input")
        }

        return Decoded(hrp, values.copyOfRange(0, values.size - 6), encoding)
    }

    /**
     * Regroups bits between arbitrary group sizes (e.g. 8-bit bytes <-> 5-bit Bech32 words). Direct port of
     * BIP173's `convertbits`.
     */
    fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Int>()
        val maxV = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1

        for (value in data) {
            if (value < 0 || (value ushr fromBits) != 0) return null

            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc ushr bits) and maxV)
            }
        }

        if (pad) {
            if (bits > 0) ret.add((acc shl (toBits - bits)) and maxV)
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxV) != 0) {
            return null
        }

        return ret.toIntArray()
    }
}

/**
 * Segwit address encoding/decoding (BIP173 witness v0, BIP350 witness v1+/Bech32m) on top of the raw [Bech32]
 * codec above. Only witness version 0 (P2WPKH/P2WSH) is actually used by this app today (native SegWit only -
 * Taproot/witness v1 is explicitly out of scope for now), but decode handles both since a user could paste any
 * valid segwit address.
 */
object SegwitAddress {

    fun encode(hrp: String, witnessVersion: Int, witnessProgram: ByteArray): String {
        require(witnessVersion in 0..16) { "Invalid witness version: $witnessVersion" }
        require(witnessProgram.size in 2..40) { "Invalid witness program length: ${witnessProgram.size}" }

        val programWords = Bech32.convertBits(witnessProgram.map { it.toInt() and 0xff }.toIntArray(), 8, 5, true)
            ?: throw IllegalArgumentException("Failed to convert witness program to 5-bit words")

        val encoding = if (witnessVersion == 0) Bech32.Encoding.BECH32 else Bech32.Encoding.BECH32M

        return Bech32.encode(hrp, intArrayOf(witnessVersion) + programWords, encoding)
    }

    data class Decoded(val witnessVersion: Int, val witnessProgram: ByteArray)

    fun decode(expectedHrp: String, address: String): Decoded {
        val (hrp, values, encoding) = Bech32.decode(address)
        require(hrp == expectedHrp) { "Unexpected HRP: expected $expectedHrp, got $hrp" }
        require(values.isNotEmpty()) { "Empty Bech32 data part: $address" }

        val witnessVersion = values[0]
        val expectedEncoding = if (witnessVersion == 0) Bech32.Encoding.BECH32 else Bech32.Encoding.BECH32M
        require(encoding == expectedEncoding) {
            "Witness version $witnessVersion requires ${expectedEncoding.name} but address used ${encoding.name}: $address"
        }

        val programWords = values.copyOfRange(1, values.size)
        val programBytes = Bech32.convertBits(programWords, 5, 8, false)
            ?: throw IllegalArgumentException("Invalid witness program padding: $address")

        require(programBytes.size in 2..40) { "Invalid witness program length: $address" }
        if (witnessVersion == 0) {
            require(programBytes.size == 20 || programBytes.size == 32) {
                "Witness v0 program must be 20 (P2WPKH) or 32 (P2WSH) bytes: $address"
            }
        }

        return Decoded(witnessVersion, ByteArray(programBytes.size) { programBytes[it].toByte() })
    }
}
