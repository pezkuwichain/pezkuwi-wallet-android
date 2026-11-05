package io.novafoundation.nova.runtime.names.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.time.Instant

/** Against records Kubo signed (`src/test/resources/kurd-ipns`, from ipfs/gateway-conformance). */
class IpnsRecordTest {

    private val now = Instant.parse("2026-11-05T00:00:00Z")

    @Test
    fun `a V2 record and one carrying matching V1 copies verify`() {
        val v2 = verify("k51qzi5uqu5dit2ku9mutlfgwyz8u730on38kd10m97m36bjt66my99hb6103f_v2")!!
        assertEquals("bafkqadtwgiww63tmpeqhezldn5zgi", v2.root.toString())

        assertEquals("bafkqaddwgevxmmraojswg33smq", verify("k51qzi5uqu5dlkw8pxuw9qmqayfdeh4kfebhmreauqdc6a7c3y7d5i9fi8mk9w_v1-v2")!!.root.toString())
    }

    @Test
    fun `a broken legacy signature does not matter, the V2 one does`() {
        assertNotNull(verify("k51qzi5uqu5dilgf7gorsh9vcqqq4myo6jd4zmqkuy9pxyxi5fua3uf7axph4y_v1-v2-broken-signature-v1"))
        assertNull(verify("k51qzi5uqu5diamp7qnnvs1p1gzmku3eijkeijs3418j23j077zrkok63xdm8c_v1-v2-broken-signature-v2"))
    }

    @Test
    fun `a V1-only record or one whose legacy value disagrees is refused`() {
        assertNull(verify("k51qzi5uqu5dm4tm0wt8srkg9h9suud4wuiwjimndrkydqm81cqtlb5ak6p7ku_v1"))
        assertNull(verify("k51qzi5uqu5dlmit2tuwdvnx4sbnyqgmvbxftl0eo3f33wwtb9gr7yozae9kpw_v1-v2-broken-v1-value"))
    }

    @Test
    fun `only the chain's publisher key is accepted`() {
        val record = resource("k51qzi5uqu5dit2ku9mutlfgwyz8u730on38kd10m97m36bjt66my99hb6103f_v2")
        val otherKey = keyOf("k51qzi5uqu5dlkw8pxuw9qmqayfdeh4kfebhmreauqdc6a7c3y7d5i9fi8mk9w")

        assertNull(IpnsRecords.verify(record, otherKey, now))
    }

    @Test
    fun `an expired record is refused`() {
        val name = "k51qzi5uqu5dit2ku9mutlfgwyz8u730on38kd10m97m36bjt66my99hb6103f"

        assertNull(IpnsRecords.verify(resource("${name}_v2"), keyOf(name), Instant.parse("2123-08-14T12:17:04Z")))
    }

    @Test
    fun `the name of a key round trips`() {
        val name = "k51qzi5uqu5dit2ku9mutlfgwyz8u730on38kd10m97m36bjt66my99hb6103f"

        assertEquals(name, IpnsRecords.nameOf(keyOf(name)))
    }

    private fun verify(file: String): IpnsRecord? = IpnsRecords.verify(resource(file), keyOf(file.substringBefore('_')), now)

    private fun resource(file: String): ByteArray = IpnsRecordTest::class.java.getResourceAsStream("/kurd-ipns/$file.ipns-record")!!.use { it.readBytes() }

    /** The ed25519 key inside a `k`-base36 IPNS name. */
    private fun keyOf(name: String): ByteArray {
        val bytes = BigInteger(name.substring(1), 36).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return bytes.copyOfRange(bytes.size - 32, bytes.size)
    }
}
