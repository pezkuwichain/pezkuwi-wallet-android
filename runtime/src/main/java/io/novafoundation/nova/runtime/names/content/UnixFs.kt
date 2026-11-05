package io.novafoundation.nova.runtime.names.content

/** A dag-pb node: `PBNode { repeated PBLink Links = 2; optional bytes Data = 1; }`, fields in any order. */
internal class PbNode(val links: List<PbLink>, val data: ByteArray?) {

    class PbLink(val cid: Cid, val name: String?, val size: Long?)

    companion object {

        fun decode(block: ByteArray): PbNode {
            val links = mutableListOf<PbLink>()
            var data: ByteArray? = null
            val reader = ByteReader(block)
            while (!reader.isExhausted) {
                when (val key = reader.varint()) {
                    // Data
                    (1L shl 3) or 2 -> {
                        require(data == null) { "Data twice" }
                        data = reader.bytes(lengthOf(reader))
                    }
                    // Links
                    (2L shl 3) or 2 -> links += decodeLink(reader.sub(lengthOf(reader)))
                    else -> throw IllegalArgumentException("Unknown dag-pb field $key")
                }
            }
            return PbNode(links, data)
        }

        private fun decodeLink(reader: ByteReader): PbLink {
            var cid: Cid? = null
            var name: String? = null
            var size: Long? = null
            while (!reader.isExhausted) {
                when (val key = reader.varint()) {
                    (1L shl 3) or 2 -> cid = Cid.parse(reader.bytes(lengthOf(reader)))
                    (2L shl 3) or 2 -> name = String(reader.bytes(lengthOf(reader)), Charsets.UTF_8)
                    (3L shl 3) or 0 -> size = reader.varint()
                    else -> throw IllegalArgumentException("Unknown dag-pb link field $key")
                }
            }
            return PbLink(cid ?: throw IllegalArgumentException("Link without a hash"), name, size)
        }
    }
}

/** UnixFS `Data`, the payload of a dag-pb node that is part of a file system. */
internal class UnixFsData(
    val type: Type,
    val data: ByteArray?,
    val fileSize: Long?,
    val blockSizes: List<Long>,
    val hashType: Long?,
    val fanout: Long?,
) {

    enum class Type { RAW, DIRECTORY, FILE, METADATA, SYMLINK, HAMT_SHARD }

    companion object {

        fun decode(bytes: ByteArray): UnixFsData {
            var type: Type? = null
            var data: ByteArray? = null
            var fileSize: Long? = null
            val blockSizes = mutableListOf<Long>()
            var hashType: Long? = null
            var fanout: Long? = null

            val reader = ByteReader(bytes)
            while (!reader.isExhausted) {
                val key = reader.varint()
                val field = key ushr 3
                when (key and 7) {
                    0L -> {
                        val value = reader.varint()
                        when (field) {
                            1L -> type = Type.values().getOrNull(value.toInt()) ?: throw IllegalArgumentException("Unknown UnixFS type $value")
                            3L -> fileSize = value
                            4L -> blockSizes += value
                            5L -> hashType = value
                            6L -> fanout = value
                            // mode (7) and other scalar metadata do not change what is served
                        }
                    }
                    2L -> {
                        val value = reader.bytes(lengthOf(reader))
                        when (field) {
                            2L -> data = value
                            // packed blocksizes
                            4L -> ByteReader(value).let { packed -> while (!packed.isExhausted) blockSizes += packed.varint() }
                            // mtime (8) and other nested metadata do not change what is served
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported UnixFS wire type")
                }
            }

            return UnixFsData(type ?: throw IllegalArgumentException("UnixFS without a type"), data, fileSize, blockSizes, hashType, fanout)
        }
    }
}

private fun lengthOf(reader: ByteReader): Int {
    val length = reader.varint()
    require(length in 0..reader.remaining.toLong()) { "Length past the end" }
    return length.toInt()
}
