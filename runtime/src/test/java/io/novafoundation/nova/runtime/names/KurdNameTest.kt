package io.novafoundation.nova.runtime.names

import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KurdNameTest {

    // The vectors pinned by pezpallet-navnisan's `namehash_vectors_are_pinned`
    @Test
    fun `nodes match the pallet's pinned vectors`() {
        assertEquals("011fc4e97a5ef275bd399ccbe8fca90e5f7a907c010663259364a9fb4b1d64d9", KurdName.KURD_NODE.toHexString())
        assertEquals("a10d2f1372dde3c6b1853b3f14f883fdd6a3ca08944d24e85b56c71249441ed5", node("ahmet.kurd"))
        assertEquals("c5c21a8e620900b2c976bc0919a91167b427de09f025bb24bf46f91a39a62907", node("gov.kurd"))
        assertEquals("3569b4cc564de96e00781bee7ad6cda51a65006529208b32e99d3be61ac7708e", node("dks.gov.kurd"))
    }

    @Test
    fun `nodes upward run from the name to its top-level name`() {
        val nodes = KurdName.parse("dks.gov.kurd")!!.nodesUpward().map { it.toHexString() }

        assertEquals(listOf(node("dks.gov.kurd"), node("gov.kurd")), nodes)
    }

    @Test
    fun `input is lower-cased and may carry a scheme, a trailing dot or slash`() {
        assertEquals("dks.kurd", KurdName.parse("  DKS.Kurd ")!!.name)
        assertEquals("serok.gov.kurd", KurdName.parse("https://Serok.Gov.Kurd/")!!.name)
        assertEquals("dks.kurd", KurdName.parse("dks.kurd.")!!.name)
        assertEquals(listOf("serok", "gov"), KurdName.parse("serok.gov.kurd")!!.labels)
        assertEquals("gov", KurdName.parse("serok.gov.kurd")!!.top)
    }

    @Test
    fun `names the pallet refuses are never parsed`() {
        listOf(
            "uni-x--edu.kurd", // `--` anywhere
            "-dks.kurd",
            "dks-.kurd",
            "dks.gov.com",
            "dks",
            ".kurd",
            "a..kurd",
            "dks_news.kurd",
            "dêrsim.kurd", // not ASCII: never lower-cased into something else
            "a.b.c.d.e.kurd", // deeper than four labels
            "dks.kurd/path",
            "dks.kurd:443",
            "http://dks.kurd",
            "user@dks.kurd",
            "a".repeat(64) + ".kurd",
        ).forEach { assertNull(it, KurdName.parse(it)) }
    }

    @Test
    fun `a label of 63 bytes and a name of four labels are accepted`() {
        assertEquals(63, KurdName.parse("a".repeat(63) + ".kurd")!!.labels.single().length)
        assertEquals(4, KurdName.parse("a.b.c.d.kurd")!!.labels.size)
    }

    @Test
    fun `any input naming a kurd host is recognised, valid name or not`() {
        listOf(
            "Uni-X--Edu.KURD/",
            "dks_news.kurd",
            "user@dks.kurd",
            "dks.kurd/path?q=1",
            "http://dks.kurd",
            "https://serok.gov.kurd:443/x",
            "dks.kurd.",
        ).forEach {
            assertEquals(it, true, KurdName.mentionsKurd(it))
        }
        listOf("kurd.com", "https://kurd.com/a.kurd/", "dkskurd", "duckduckgo.com/?q=dks").forEach {
            assertEquals(it, false, KurdName.mentionsKurd(it))
        }
    }

    @Test
    fun `kurd hosts include the bare suffix and a trailing dot`() {
        listOf("kurd", "dks.kurd", "DKS.KURD.", "a.b.gov.kurd").forEach { assertEquals(it, true, KurdName.isKurdHost(it)) }
        listOf(null, "kurd.com", "notkurd", "dks.kurd.com").forEach { assertEquals(it.toString(), false, KurdName.isKurdHost(it)) }
    }

    private fun node(name: String) = KurdName.parse(name)!!.node.toHexString()
}
