package io.novafoundation.nova.runtime.names.content

/**
 * Walks a UnixFS tree held in [blocks] and assembles files from it. Every block was checked
 * against its CID when the CAR was read, and every step here follows a CID taken from a block
 * already checked, so what comes out is exactly what the root CID names.
 */
class UnixFsReader(private val blocks: VerifiedBlocks, private val maxFileSize: Int = DEFAULT_MAX_FILE) {

    sealed interface Entry {
        class File(val bytes: ByteArray) : Entry
        class Directory(val cid: Cid) : Entry
    }

    /** The entry at [path] (segments, already split and decoded) under [root]. Null when nothing is there. */
    fun read(root: Cid, path: List<String>): Entry? = verify {
        var cid = root
        for (segment in path) {
            require(segment.isNotEmpty() && segment != "." && segment != ".." && '/' !in segment) { "Bad path segment" }
            cid = child(cid, segment) ?: return@verify null
        }
        entry(cid)
    }

    private fun entry(cid: Cid): Entry {
        if (cid.codec == Cid.Codec.RAW) return Entry.File(blocks.get(cid).also(::checkSize))

        val node = PbNode.decode(blocks.get(cid))
        val unixFs = UnixFsData.decode(node.data ?: throw IllegalArgumentException("dag-pb node without UnixFS data"))
        return when (unixFs.type) {
            UnixFsData.Type.FILE, UnixFsData.Type.RAW -> Entry.File(assemble(node, unixFs))
            UnixFsData.Type.DIRECTORY, UnixFsData.Type.HAMT_SHARD -> Entry.Directory(cid)
            UnixFsData.Type.SYMLINK, UnixFsData.Type.METADATA -> throw ContentVerificationException("Unsupported UnixFS entry ${unixFs.type}")
        }
    }

    private fun child(directory: Cid, name: String): Cid? {
        if (directory.codec != Cid.Codec.DAG_PB) return null
        val node = PbNode.decode(blocks.get(directory))
        val unixFs = UnixFsData.decode(node.data ?: throw IllegalArgumentException("dag-pb node without UnixFS data"))

        return when (unixFs.type) {
            UnixFsData.Type.DIRECTORY -> node.links.singleOrNull { it.name == name }?.cid
            UnixFsData.Type.HAMT_SHARD -> shardLookup(node, unixFs, name, HashBits(murmur3x64_64(name.toByteArray(Charsets.UTF_8))), depth = 0)
            else -> null
        }
    }

    /**
     * HAMT directory lookup, as `go-unixfs`/`boxo` shard: each level consumes log2(fanout) bits of
     * the name's murmur3-x64-64 hash, most significant first; a link named by the bucket index
     * alone (upper-case hex) is a child shard, one named by index plus the entry name is the entry.
     */
    private fun shardLookup(node: PbNode, unixFs: UnixFsData, name: String, bits: HashBits, depth: Int): Cid? {
        require(unixFs.hashType == MURMUR3_X64_64) { "Unsupported HAMT hash" }
        val fanout = unixFs.fanout ?: throw IllegalArgumentException("HAMT without fanout")
        require(fanout in 2..MAX_FANOUT && fanout and (fanout - 1) == 0L) { "Bad HAMT fanout" }
        val width = java.lang.Long.numberOfTrailingZeros(fanout)
        require(depth < 64 / width) { "HAMT deeper than its hash" }

        val padLength = (fanout - 1).toString(16).length
        val prefix = bits.next(width).toString(16).uppercase().padStart(padLength, '0')

        for (link in node.links) {
            val linkName = link.name ?: continue
            if (!linkName.startsWith(prefix)) continue
            if (linkName.length == padLength) {
                val shard = PbNode.decode(blocks.get(link.cid))
                val shardData = UnixFsData.decode(shard.data ?: throw IllegalArgumentException("Shard without data"))
                require(shardData.type == UnixFsData.Type.HAMT_SHARD && shardData.fanout == fanout) { "Inconsistent HAMT shard" }
                return shardLookup(shard, shardData, name, bits, depth + 1)
            }
            if (linkName.substring(padLength) == name) return link.cid
        }
        return null
    }

    private fun assemble(node: PbNode, unixFs: UnixFsData): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        appendFile(node, unixFs, out, depth = 0)
        return out.toByteArray()
    }

    /** A file node's own data comes first, then its children in link order. */
    private fun appendFile(node: PbNode, unixFs: UnixFsData, out: java.io.ByteArrayOutputStream, depth: Int) {
        require(depth < MAX_FILE_DEPTH) { "File tree too deep" }
        unixFs.data?.let { append(out, it) }

        for (link in node.links) {
            if (link.cid.codec == Cid.Codec.RAW) {
                append(out, blocks.get(link.cid))
                continue
            }
            val child = PbNode.decode(blocks.get(link.cid))
            val childData = UnixFsData.decode(child.data ?: throw IllegalArgumentException("File part without data"))
            require(childData.type == UnixFsData.Type.FILE || childData.type == UnixFsData.Type.RAW) { "File part is not a file" }
            appendFile(child, childData, out, depth + 1)
        }
    }

    private fun append(out: java.io.ByteArrayOutputStream, bytes: ByteArray) {
        checkSize(out.size().toLong() + bytes.size)
        out.write(bytes)
    }

    private fun checkSize(bytes: ByteArray) = checkSize(bytes.size.toLong())

    private fun checkSize(size: Long) {
        if (size > maxFileSize) throw ContentVerificationException("File larger than $maxFileSize bytes")
    }

    private inline fun <T> verify(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ContentVerificationException("Malformed UnixFS: ${e.message}")
    }

    /** The bits of a hash, most significant first. */
    private class HashBits(private val hash: Long) {

        private var used = 0

        fun next(count: Int): Long {
            require(used + count <= 64) { "HAMT hash exhausted" }
            val value = (hash ushr (64 - used - count)) and ((1L shl count) - 1)
            used += count
            return value
        }
    }

    companion object {

        const val DEFAULT_MAX_FILE = 32 * 1024 * 1024

        /** Multicodec `murmur3-x64-64`, the only hash UnixFS shards use. */
        private const val MURMUR3_X64_64 = 0x22L
        private const val MAX_FANOUT = 1024L
        private const val MAX_FILE_DEPTH = 32

        /** The first 64 bits of MurmurHash3 x64 128 with seed 0 (`murmur3.New64` in Go). */
        internal fun murmur3x64_64(data: ByteArray): Long {
            val c1 = -0x783c846eeebdac2bL // 0x87c37b91114253d5
            val c2 = 0x4cf5ad432745937fL
            var h1 = 0L
            var h2 = 0L

            fun littleEndian(offset: Int, count: Int): Long {
                var value = 0L
                for (i in count - 1 downTo 0) value = (value shl 8) or (data[offset + i].toLong() and 0xff)
                return value
            }

            val blocks = data.size / 16
            for (block in 0 until blocks) {
                var k1 = littleEndian(block * 16, 8)
                var k2 = littleEndian(block * 16 + 8, 8)

                k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1
                h1 = java.lang.Long.rotateLeft(h1, 27); h1 += h2; h1 = h1 * 5 + 0x52dce729

                k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2
                h2 = java.lang.Long.rotateLeft(h2, 31); h2 += h1; h2 = h2 * 5 + 0x38495ab5
            }

            val tail = blocks * 16
            val tailLength = data.size - tail
            if (tailLength > 8) {
                var k2 = littleEndian(tail + 8, tailLength - 8)
                k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2
            }
            if (tailLength > 0) {
                var k1 = littleEndian(tail, minOf(tailLength, 8))
                k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1
            }

            h1 = h1 xor data.size.toLong()
            h2 = h2 xor data.size.toLong()
            h1 += h2
            h2 += h1
            h1 = fmix(h1)
            h2 = fmix(h2)
            h1 += h2

            return h1
        }

        private fun fmix(value: Long): Long {
            var k = value
            k = k xor (k ushr 33)
            k *= -0xae502812aa7333L // 0xff51afd7ed558ccd
            k = k xor (k ushr 33)
            k *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
            k = k xor (k ushr 33)
            return k
        }
    }
}
