package io.novafoundation.nova.runtime.names

import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameRecordTest {

    @Test
    fun `a zone granted at genesis decodes as governance owned and official`() {
        val gov = NameRecord.decode(NameFixtures.GOV)

        assertEquals("gov", gov.label)
        assertEquals(KurdName.KURD_NODE.toHexString(), gov.parent.toHexString())
        assertEquals(1L, gov.reg)
        assertTrue(gov.owner is NameRecord.Owner.Governance)
        assertEquals(NameRecord.Kind.GRANTED, gov.kind)
        assertTrue(gov.lockedTransfer)
        assertNull(gov.expiry)
        assertNull(gov.account)
        assertNull(gov.content)
        assertTrue(gov.isOfficial)
        assertTrue(gov.isInstitutional)
    }

    @Test
    fun `a ministry subname decodes as office owned with its account`() {
        val serok = NameRecord.decode(NameFixtures.SEROK_GOV)

        assertEquals("serok", serok.label)
        assertEquals(KurdName.parse("gov.kurd")!!.node.toHexString(), serok.parent.toHexString())
        assertEquals(1L, serok.parentReg)
        assertEquals(3, (serok.owner as NameRecord.Owner.Office).tiki)
        assertEquals(NameRecord.Kind.SUB, serok.kind)
        assertEquals(32, serok.account!!.size)
        assertTrue(serok.isOfficial)

        assertEquals(0x25, (NameRecord.decode(NameFixtures.DARAYI_GOV).owner as NameRecord.Owner.Office).tiki)
    }

    @Test
    fun `a trailing byte is refused rather than ignored`() {
        val result = runCatching { NameRecord.decode(NameFixtures.DKS + byteArrayOf(0)) }

        assertFalse(result.isSuccess)
    }

    @Test
    fun `content variants decode`() {
        val cid = byteArrayOf(0xe3.toByte(), 1, 2, 3)
        val withIpfs = NameFixtures.DKS.copyOf(NameFixtures.DKS.size - 2) + byteArrayOf(1, 0, (cid.size shl 2).toByte()) + cid + byteArrayOf(0)

        val content = NameRecord.decode(withIpfs).content as NameRecord.Content.Ipfs

        assertEquals(cid.toHexString(), content.contenthash.toHexString())
    }
}
