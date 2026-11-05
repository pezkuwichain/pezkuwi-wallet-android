package io.novafoundation.nova.runtime.names.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CidTest {

    private val root = cid("bafybeietjm63oynimmv5yyqay33nui4y4wx6u3peezwetxgiwvfmelutzu")

    @Test
    fun `base32 form round trips`() {
        assertEquals("bafybeietjm63oynimmv5yyqay33nui4y4wx6u3peezwetxgiwvfmelutzu", root.toString())
        assertEquals(Cid.Codec.DAG_PB, root.codec)
        assertEquals(Cid.Codec.RAW, cid("bafkreifjjcie6lypi6ny7amxnfftagclbuxndqonfipmb64f2km2devei4").codec)
    }

    @Test
    fun `an ipfs contenthash as Navnisan stores it gives its CID`() {
        assertEquals(root, Cid.fromContenthash(byteArrayOf(0xe3.toByte(), 0x01) + root.bytes))
    }

    @Test
    fun `other contenthashes are not content`() {
        assertNull("ipns", Cid.fromContenthash(byteArrayOf(0xe5.toByte(), 0x01) + root.bytes))
        assertNull("trailing byte", Cid.fromContenthash(byteArrayOf(0xe3.toByte(), 0x01) + root.bytes + 0))
        assertNull("truncated", Cid.fromContenthash(byteArrayOf(0xe3.toByte(), 0x01) + root.bytes.copyOf(10)))
        // An identity CID would put the site in the record itself; a root must be a real hash
        assertNull("identity root", Cid.fromContenthash(byteArrayOf(0xe3.toByte(), 0x01, 0x01, 0x55, 0x00, 0x02, 0x68, 0x69)))
    }

    @Test
    fun `a block matches only its own CID`() {
        val hello = cid("bafkreifjjcie6lypi6ny7amxnfftagclbuxndqonfipmb64f2km2devei4")

        assertTrue(hello.matches("hello world\n".toByteArray()))
        assertFalse(hello.matches("hello world!".toByteArray()))
    }

    @Test
    fun `unsupported codecs and hashes are refused`() {
        // dag-cbor (0x71)
        assertTrue(runCatching { Cid.parse(byteArrayOf(0x01, 0x71, 0x12, 0x20) + ByteArray(32)) }.isFailure)
        // sha2-512 (0x13)
        assertTrue(runCatching { Cid.parse(byteArrayOf(0x01, 0x55, 0x13, 0x40) + ByteArray(64)) }.isFailure)
        // sha2-256 with a short digest
        assertTrue(runCatching { Cid.parse(byteArrayOf(0x01, 0x55, 0x12, 0x10) + ByteArray(16)) }.isFailure)
    }

    @Test
    fun `an inlined block needs no fetch and is its own content`() {
        val inline = Cid.parse(byteArrayOf(0x01, 0x55, 0x00, 0x02, 0x68, 0x69))

        assertEquals("hi", String(inline.inlineBlock!!))
        assertTrue(inline.matches("hi".toByteArray()))
    }
}
