package io.novafoundation.nova.runtime.names.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against CARs Kubo produced (`src/test/resources/kurd-content`, from ipfs/gateway-conformance). */
class UnixFsReaderTest {

    @Test
    fun `files in a nested directory are read`() {
        val reader = reader("subdir-with-two-single-block-files.car")
        val root = cid("bafybeietjm63oynimmv5yyqay33nui4y4wx6u3peezwetxgiwvfmelutzu")

        assertEquals("hello world\n", text(reader.read(root, listOf("subdir", "hello.txt"))))
        assertEquals("hello application/vnd.ipld.car\n", text(reader.read(root, listOf("subdir", "ascii.txt"))))
        assertTrue(reader.read(root, listOf("subdir")) is UnixFsReader.Entry.Directory)
        assertTrue(reader.read(root, emptyList()) is UnixFsReader.Entry.Directory)
        assertNull(reader.read(root, listOf("subdir", "missing.txt")))
    }

    @Test
    fun `a file chunked into several blocks is assembled in order`() {
        val reader = reader("dir-with-files.car")
        val root = cid("bafybeihchr7vmgjaasntayyatmp5sv6xza57iy2h4xj7g46bpjij6yhrmy")

        val text = text(reader.read(root, listOf("multiblock.txt")))

        assertEquals(1026, text.length)
        assertTrue(text.startsWith("Lorem ipsum dolor sit amet, consectetur adipiscing elit."))
        assertTrue(text.endsWith("Pellentesque habitant amet."))
        assertEquals(text(reader.read(root, listOf("ascii.txt"))), text(reader.read(root, listOf("ascii-copy.txt"))))
    }

    @Test
    fun `a sharded directory is looked up by the name's hash`() {
        val reader = reader("single-layer-hamt-with-multi-block-files.car")
        val root = cid("bafybeidbclfqleg2uojchspzd4bob56dqetqjsj27gy2cq3klkkgxtpn4i")

        listOf("470.txt", "742.txt", "448.txt", "499.txt").forEach { name ->
            assertTrue(name, text(reader.read(root, listOf(name))).startsWith("Lorem ipsum"))
        }
        assertNull(reader.read(root, listOf("6E470.txt")))
    }

    @Test
    fun `node fields in either order and shards with one entry read the same`() {
        val reader = reader("pbnode-field-orders.car")

        listOf(
            "bafybeigqvyloizmfcdy6scaxnyltftzptaruqa3hnnplfzsbf4sqteiwlm",
            "bafybeigdcg7pksx2zk5336vrfsktjodlr4rbfz37qr3koc5xboxe5ekv24",
            "bafybeicwgy2rlqmqqu3yy2tqvm2wbgdvy3snu4sbbv4wqpvpnoplpzxz74",
            "bafybeicjwkfslu7gwyywffvqgse5kiibojtktxcdqhgv7ldj5fjdacuceq",
        ).forEach { root -> assertEquals(root, "hello\n", text(reader.read(cid(root), listOf("hello.txt")))) }
    }

    @Test
    fun `a file with a block no mirror sent is not served`() {
        val reader = reader("file-3k-and-3-blocks-missing-block.car")
        val root = Cid.parse(hex("122099fd9f8119c50b421e8e87d7047f6bb7cc4d4d5cfecea65813fb4bfef5049b79"))

        assertTrue(runCatching { reader.read(root, emptyList()) }.exceptionOrNull() is ContentVerificationException)
    }

    @Test
    fun `a CAR with any block changed is refused whole`() {
        val car = resource("subdir-with-two-single-block-files.car")
        val tampered = car.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() } // inside "hello world\n"

        assertTrue(runCatching { VerifiedBlocks.fromCar(tampered) }.exceptionOrNull() is ContentVerificationException)
    }

    @Test
    fun `a truncated CAR is refused`() {
        val car = resource("subdir-with-two-single-block-files.car")

        assertTrue(runCatching { VerifiedBlocks.fromCar(car.copyOf(car.size - 3)) }.exceptionOrNull() is ContentVerificationException)
    }

    @Test
    fun `a file larger than the limit is not assembled`() {
        val reader = UnixFsReader(VerifiedBlocks.fromCar(resource("dir-with-files.car")), maxFileSize = 1000)
        val root = cid("bafybeihchr7vmgjaasntayyatmp5sv6xza57iy2h4xj7g46bpjij6yhrmy")

        assertTrue(runCatching { reader.read(root, listOf("multiblock.txt")) }.exceptionOrNull() is ContentVerificationException)
    }

    @Test
    fun `path segments that could escape are refused`() {
        val reader = reader("subdir-with-two-single-block-files.car")
        val root = cid("bafybeietjm63oynimmv5yyqay33nui4y4wx6u3peezwetxgiwvfmelutzu")

        listOf("..", ".", "", "subdir/hello.txt").forEach { segment ->
            assertTrue(segment, runCatching { reader.read(root, listOf(segment)) }.exceptionOrNull() is ContentVerificationException)
        }
    }

    @Test
    fun `murmur3 matches the shard prefix Kubo gave hello txt`() {
        assertEquals(0xDF, (UnixFsReader.murmur3x64_64("hello.txt".toByteArray()) ushr 56).toInt())
    }

    // Guava's Murmur3_128 vector: x64_128 = 6c1b07bc7bbc4be3 47939ac4a93c437a, h1 written little-endian.
    // 43 bytes, so both 16-byte blocks and both tail halves are exercised.
    @Test
    fun `murmur3 matches the reference vector over blocks and tail`() {
        val h1 = UnixFsReader.murmur3x64_64("The quick brown fox jumps over the lazy dog".toByteArray())

        assertEquals("e34bbc7bbc071b6c", java.lang.Long.toHexString(h1))
    }

    private fun reader(name: String) = UnixFsReader(VerifiedBlocks.fromCar(resource(name)))

    private fun text(entry: UnixFsReader.Entry?) = String((entry as UnixFsReader.Entry.File).bytes, Charsets.UTF_8)
}

internal fun resource(name: String): ByteArray = UnixFsReaderTest::class.java.getResourceAsStream("/kurd-content/$name")!!.use { it.readBytes() }

internal fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/** Multibase base32 (`b…`) to a CID, for writing fixtures' CIDs as they are published. */
internal fun cid(value: String): Cid {
    require(value.startsWith("b"))
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (char in value.substring(1)) {
        buffer = (buffer shl 5) or alphabet.indexOf(char).also { require(it >= 0) }
        bits += 5
        if (bits >= 8) {
            out.write((buffer shr (bits - 8)) and 0xff)
            bits -= 8
        }
    }
    return Cid.parse(out.toByteArray())
}
