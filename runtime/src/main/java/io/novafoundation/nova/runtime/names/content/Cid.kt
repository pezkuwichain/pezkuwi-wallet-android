package io.novafoundation.nova.runtime.names.content

import java.security.MessageDigest

/**
 * A content identifier, limited to what `.kurd` sites are built from: CIDv1 or CIDv0, codec dag-pb
 * or raw, hash sha2-256 or identity (inlined tiny blocks). Anything else is refused when parsed,
 * so a block can only ever be accepted by checking its bytes against the digest.
 */
class Cid private constructor(
    /** The CID's binary form, as it appears in links and in a CAR. */
    val bytes: ByteArray,
    val codec: Codec,
    private val hash: Hash,
    private val digest: ByteArray,
) {

    enum class Codec(val code: Int) { DAG_PB(0x70), RAW(0x55) }

    private enum class Hash(val code: Int) { IDENTITY(0x00), SHA2_256(0x12) }

    /** True when [block] is the content this CID names. */
    fun matches(block: ByteArray): Boolean = when (hash) {
        Hash.IDENTITY -> MessageDigest.isEqual(digest, block)
        Hash.SHA2_256 -> MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(block))
    }

    /** An identity CID carries its block; nothing needs to be fetched for it. */
    val inlineBlock: ByteArray? get() = digest.copyOf().takeIf { hash == Hash.IDENTITY }

    /** Multibase base32 (`b…`), the form gateways take in a path. */
    override fun toString(): String = "b" + base32(bytes)

    override fun equals(other: Any?) = other is Cid && other.bytes.contentEquals(bytes)

    override fun hashCode() = bytes.contentHashCode()

    companion object {

        private const val SHA2_256_LENGTH = 32

        /** An inlined block is meant for a few bytes; a larger one is not something a publisher makes. */
        private const val MAX_IDENTITY_LENGTH = 128

        /**
         * The CID in an EIP-1577 contenthash as `Navnisan` stores it, or null when it is not an
         * `ipfs-ns` (`0xe3 0x01`) hash of a supported CID. `ipns-ns` is not content but a pointer
         * that needs its own signed record, so it is not accepted here.
         */
        fun fromContenthash(contenthash: ByteArray): Cid? {
            if (contenthash.size < 2 || contenthash[0] != 0xe3.toByte() || contenthash[1] != 0x01.toByte()) return null
            val reader = ByteReader(contenthash, 2)
            return runCatching { read(reader) }.getOrNull()?.takeIf { reader.isExhausted && it.hash == Hash.SHA2_256 }
        }

        /** The CID that fills [bytes] exactly. */
        fun parse(bytes: ByteArray): Cid {
            val reader = ByteReader(bytes, 0)
            return read(reader).also { require(reader.isExhausted) { "Trailing bytes after a CID" } }
        }

        /** Reads one CID at the reader's position: CIDv0 is a bare sha2-256 multihash (`0x12 0x20`). */
        internal fun read(reader: ByteReader): Cid {
            val start = reader.position
            if (reader.peek() == Hash.SHA2_256.code) {
                val digest = readMultihashDigest(reader, Hash.SHA2_256)
                return Cid(reader.slice(start), Codec.DAG_PB, Hash.SHA2_256, digest)
            }

            require(reader.varint() == 1L) { "Unsupported CID version" }
            val codecCode = reader.varint()
            val codec = Codec.values().firstOrNull { it.code.toLong() == codecCode } ?: throw IllegalArgumentException("Unsupported codec $codecCode")
            val hashCode = reader.varint()
            val hash = Hash.values().firstOrNull { it.code.toLong() == hashCode } ?: throw IllegalArgumentException("Unsupported hash $hashCode")
            val digest = readDigest(reader, hash)

            return Cid(reader.slice(start), codec, hash, digest)
        }

        private fun readMultihashDigest(reader: ByteReader, expected: Hash): ByteArray {
            require(reader.varint() == expected.code.toLong()) { "Unexpected hash" }
            return readDigest(reader, expected)
        }

        private fun readDigest(reader: ByteReader, hash: Hash): ByteArray {
            val length = reader.varint()
            when (hash) {
                Hash.SHA2_256 -> require(length == SHA2_256_LENGTH.toLong()) { "Bad sha2-256 length" }
                Hash.IDENTITY -> require(length in 0..MAX_IDENTITY_LENGTH) { "Identity block too long" }
            }
            return reader.bytes(length.toInt())
        }

        private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

        private fun base32(data: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0
            var bits = 0
            for (byte in data) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    out.append(BASE32[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) out.append(BASE32[(buffer shl (5 - bits)) and 31])
            return out.toString()
        }
    }
}

/** Bounded reads over bytes that came from the network: every read checks it stays inside. */
internal class ByteReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {

    var position = start
        private set

    val isExhausted: Boolean get() = position == end

    val remaining: Int get() = end - position

    fun peek(): Int {
        require(position < end) { "Unexpected end of input" }
        return data[position].toInt() and 0xff
    }

    fun byte(): Int = peek().also { position++ }

    fun bytes(count: Int): ByteArray {
        require(count in 0..remaining) { "Unexpected end of input" }
        return data.copyOfRange(position, position + count).also { position += count }
    }

    /** Unsigned LEB128, at most 63 bits and minimally encoded, as multiformats require. */
    fun varint(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            val byte = byte()
            require(shift < 63) { "Varint too long" }
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) {
                require(byte != 0 || shift == 0) { "Varint not minimally encoded" }
                return value
            }
            shift += 7
        }
    }

    /** A reader over the next [count] bytes; this reader moves past them. */
    fun sub(count: Int): ByteReader {
        require(count in 0..remaining) { "Unexpected end of input" }
        return ByteReader(data, position, position + count).also { position += count }
    }

    fun slice(from: Int): ByteArray = data.copyOfRange(from, position)
}
