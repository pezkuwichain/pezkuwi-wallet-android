package io.novafoundation.nova.runtime.names

import io.novafoundation.nova.runtime.names.RealKurdNameResolver.Companion.judge
import io.novafoundation.nova.runtime.names.RealKurdNameResolver.Companion.refuseOrGiveUp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KurdResolutionJudgeTest {

    private val dks = KurdName.parse("dks.kurd")!!
    private val ahmet = KurdName.parse("ahmet.kurd")!!

    @Test
    fun `a proven official name resolves as official`() {
        val resolution = judge(dks, found(NameFixtures.DKS), verified = true) as KurdResolution.Resolved

        assertTrue(resolution.verified)
        assertTrue(resolution.official)
    }

    @Test
    fun `an unproven official, institutional, reserved or zone name never opens`() {
        assertTrue(judge(dks, found(NameFixtures.DKS), verified = false) is KurdResolution.Refused)
        assertTrue(judge(ahmet, found(leased(), locked = true), verified = false) is KurdResolution.Refused)
        assertTrue(judge(KurdName.parse("ahmet.edu.kurd")!!, found(leased()), verified = false) is KurdResolution.Refused)
        assertTrue(judge(ahmet, found(leased(owner = NameRecord.Owner.Governance)), verified = false) is KurdResolution.Refused)
    }

    @Test
    fun `an unproven bought name opens with the warning and never as official`() {
        val resolution = judge(ahmet, found(leased()), verified = false) as KurdResolution.Resolved

        assertFalse(resolution.verified)
        assertFalse(resolution.official)
    }

    @Test
    fun `with no answer a zone name is refused and any other is unavailable`() {
        assertTrue(refuseOrGiveUp(KurdName.parse("serok.gov.kurd")!!) is KurdResolution.Refused)
        assertTrue(refuseOrGiveUp(ahmet) is KurdResolution.Unavailable)
    }

    private fun found(raw: ByteArray, locked: Boolean = false) = found(NameRecord.decode(raw), locked)

    private fun found(record: NameRecord, locked: Boolean = false) = NameLookup.Found(record, NameStatus.ACTIVE, listOf("raw"), locked)

    private fun leased(owner: NameRecord.Owner = NameRecord.Owner.Account(ByteArray(32))) = NameRecord(
        label = "ahmet", parent = KurdName.KURD_NODE, parentReg = 0, reg = 9, owner = owner, kind = NameRecord.Kind.LEASED,
        lockedTransfer = false, expiry = Long.MAX_VALUE, account = null, content = null,
    )
}
