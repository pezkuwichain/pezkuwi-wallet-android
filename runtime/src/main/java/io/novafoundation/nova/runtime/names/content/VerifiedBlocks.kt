package io.novafoundation.nova.runtime.names.content

class ContentVerificationException(message: String) : Exception(message)

/**
 * Blocks whose bytes have each been checked against their CID. Built only from a CAR the network
 * sent, so a mirror can leave blocks out (the read then fails) but cannot change one.
 */
class VerifiedBlocks private constructor(private val blocks: Map<Cid, ByteArray>) {

    /** The block named by [cid], or a failure when no mirror sent it. */
    fun get(cid: Cid): ByteArray = cid.inlineBlock ?: blocks[cid] ?: throw ContentVerificationException("Missing block $cid")

    val size: Int get() = blocks.size

    companion object {

        /** A block larger than this is not one IPFS tooling produces (its limit is 1 MiB, plus framing). */
        const val MAX_BLOCK = 2 * 1024 * 1024

        private const val MAX_HEADER = 1024

        /**
         * Reads a CARv1 stream. Every block must match its CID or the whole CAR is refused: a mirror
         * that changes one byte is not a mirror. The header's roots are not trusted; the caller
         * starts from the CID the chain gave.
         */
        fun fromCar(car: ByteArray): VerifiedBlocks {
            val reader = ByteReader(car)
            try {
                val headerLength = reader.varint()
                require(headerLength in 1..MAX_HEADER) { "Bad CAR header length" }
                requireCarV1(reader.bytes(headerLength.toInt()))

                val blocks = mutableMapOf<Cid, ByteArray>()
                while (!reader.isExhausted) {
                    val sectionLength = reader.varint()
                    require(sectionLength in 1..(MAX_BLOCK + 128).toLong()) { "Bad CAR section length" }
                    val section = reader.sub(sectionLength.toInt())
                    val cid = Cid.read(section)
                    val block = section.bytes(section.remaining)
                    if (!cid.matches(block)) throw ContentVerificationException("Block does not match $cid")
                    blocks[cid] = block
                }

                return VerifiedBlocks(blocks)
            } catch (e: IllegalArgumentException) {
                throw ContentVerificationException("Malformed CAR: ${e.message}")
            }
        }

        /** Header is the dag-cbor map `{roots: [...], version: 1}`; only the version is read. */
        private fun requireCarV1(header: ByteArray) {
            val versionKey = byteArrayOf(0x67) + "version".toByteArray()
            val at = indexOf(header, versionKey)
            require(at >= 0 && at + versionKey.size < header.size && header[at + versionKey.size] == 0x01.toByte()) { "Not a CARv1" }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (start in 0..haystack.size - needle.size) {
                for (offset in needle.indices) if (haystack[start + offset] != needle[offset]) continue@outer
                return start
            }
            return -1
        }
    }
}
