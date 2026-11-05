package io.novafoundation.nova.runtime.names.content

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.time.Instant

/** A feed release: the content root its publisher signed, and the release's number. */
class IpnsRecord(val root: Cid, val sequence: Long)

/**
 * Verifies IPNS records (ipfs/specs `ipns-record`, V2) against a feed's publisher key as the chain
 * gives it. The key in the record or in its name is never used: a mirror cannot answer for a feed
 * with a record signed by someone else.
 */
object IpnsRecords {

    /** The spec's ceiling: a larger record is refused unread. */
    const val MAX_RECORD = 10 * 1024

    private val SIGNATURE_PREFIX = "ipns-signature:".toByteArray()

    /**
     * The release in [record], or null when it is not one [publisherKey] (ed25519) signed, has
     * expired by [now], or points anywhere but at an immutable `/ipfs/` root.
     */
    fun verify(record: ByteArray, publisherKey: ByteArray, now: Instant): IpnsRecord? = runCatching {
        require(record.size <= MAX_RECORD && publisherKey.size == 32)
        val entry = IpnsEntry.decode(record)
        val data = entry.data ?: return null
        val signature = entry.signatureV2 ?: return null

        val signer = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publisherKey, 0))
            update(SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX.size)
            update(data, 0, data.size)
        }
        if (!signer.verifySignature(signature)) return null

        val signed = Cbor.decodeMap(data)
        val value = signed["Value"] as? ByteArray ?: return null
        val validity = signed["Validity"] as? ByteArray ?: return null
        val validityType = signed["ValidityType"] as? BigInteger ?: return null
        val sequence = signed["Sequence"] as? BigInteger ?: return null

        // Legacy copies outside the signed data must say the same, or they would be read instead of it
        entry.value?.let { if (!it.contentEquals(value)) return null }
        entry.validity?.let { if (!it.contentEquals(validity)) return null }
        entry.validityType?.let { if (it != validityType.toLong()) return null }
        entry.sequence?.let { if (it != sequence.toLong()) return null }

        // EOL is the only validity type there is
        if (validityType != BigInteger.ZERO) return null
        if (!Instant.parse(String(validity, Charsets.US_ASCII)).isAfter(now)) return null

        val path = String(value, Charsets.UTF_8)
        if (!path.startsWith("/ipfs/")) return null
        val root = CidText.parse(path.removePrefix("/ipfs/").substringBefore('/')) ?: return null

        IpnsRecord(root, sequence.longValueExact())
    }.getOrNull()

    /** The IPNS name of an ed25519 key: `k`-base36 CIDv1, codec libp2p-key, identity multihash of its protobuf form. */
    fun nameOf(publisherKey: ByteArray): String {
        val protobufKey = byteArrayOf(0x08, 0x01, 0x12, 0x20) + publisherKey
        val cid = byteArrayOf(0x01, 0x72, 0x00, protobufKey.size.toByte()) + protobufKey
        return "k" + base36(cid)
    }

    private fun base36(data: ByteArray): String {
        val zeros = data.takeWhile { it == 0.toByte() }.size
        val digits = BigInteger(1, data).toString(36).takeUnless { it == "0" }.orEmpty()
        return "0".repeat(zeros) + digits
    }

    private class IpnsEntry(
        val value: ByteArray?,
        val validityType: Long?,
        val validity: ByteArray?,
        val sequence: Long?,
        val signatureV2: ByteArray?,
        val data: ByteArray?,
    ) {
        companion object {
            fun decode(bytes: ByteArray): IpnsEntry {
                var value: ByteArray? = null
                var validityType: Long? = null
                var validity: ByteArray? = null
                var sequence: Long? = null
                var signatureV2: ByteArray? = null
                var data: ByteArray? = null

                val reader = ByteReader(bytes)
                while (!reader.isExhausted) {
                    val key = reader.varint()
                    when (key and 7) {
                        0L -> {
                            val number = reader.varint()
                            when (key ushr 3) {
                                3L -> validityType = number
                                5L -> sequence = number
                            }
                        }
                        2L -> {
                            val length = reader.varint()
                            require(length in 0..reader.remaining.toLong())
                            val field = reader.bytes(length.toInt())
                            when (key ushr 3) {
                                1L -> value = field
                                4L -> validity = field
                                8L -> signatureV2 = field
                                9L -> data = field
                            }
                        }
                        else -> throw IllegalArgumentException("Unsupported wire type")
                    }
                }
                return IpnsEntry(value, validityType, validity, sequence, signatureV2, data)
            }
        }
    }
}

/** The DAG-CBOR an IPNS record signs: one map of text keys to byte strings, text and unsigned integers. */
internal object Cbor {

    fun decodeMap(bytes: ByteArray): Map<String, Any> {
        val reader = ByteReader(bytes)
        val (major, count) = head(reader)
        require(major == 5) { "Not a map" }
        val map = mutableMapOf<String, Any>()
        repeat(count.toInt()) {
            val key = item(reader) as? String ?: throw IllegalArgumentException("Map key is not text")
            map[key] = item(reader)
        }
        require(reader.isExhausted) { "Trailing bytes after the map" }
        return map
    }

    private fun item(reader: ByteReader): Any {
        val (major, argument) = head(reader)
        return when (major) {
            0 -> argument
            2 -> reader.bytes(length(reader, argument))
            3 -> String(reader.bytes(length(reader, argument)), Charsets.UTF_8)
            else -> throw IllegalArgumentException("Unsupported CBOR major type $major")
        }
    }

    private fun length(reader: ByteReader, argument: BigInteger): Int {
        require(argument <= BigInteger.valueOf(reader.remaining.toLong())) { "Length past the end" }
        return argument.toInt()
    }

    private fun head(reader: ByteReader): Pair<Int, BigInteger> {
        val initial = reader.byte()
        val major = initial ushr 5
        val info = initial and 0x1f
        val argument = when {
            info < 24 -> BigInteger.valueOf(info.toLong())
            info == 24 -> BigInteger.valueOf(reader.byte().toLong())
            info == 25 -> BigInteger(1, reader.bytes(2))
            info == 26 -> BigInteger(1, reader.bytes(4))
            info == 27 -> BigInteger(1, reader.bytes(8))
            else -> throw IllegalArgumentException("Indefinite or reserved CBOR length")
        }
        return major to argument
    }
}

/** A CID as text in a path: multibase base32 (`b…`) or a CIDv0 in base58btc (`Qm…`). */
internal object CidText {

    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun parse(text: String): Cid? = runCatching {
        when {
            text.startsWith("Qm") && text.length == 46 -> Cid.parse(base58(text))
            text.startsWith("b") -> Cid.parse(base32(text.substring(1)))
            else -> null
        }
    }.getOrNull()

    private fun base32(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in text) {
            val value = BASE32.indexOf(char)
            require(value >= 0) { "Not base32" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    private fun base58(text: String): ByteArray {
        var number = BigInteger.ZERO
        for (char in text) {
            val value = BASE58.indexOf(char)
            require(value >= 0) { "Not base58" }
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(value.toLong()))
        }
        val bytes = number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val zeros = text.takeWhile { it == '1' }.length
        return ByteArray(zeros) + bytes
    }
}
