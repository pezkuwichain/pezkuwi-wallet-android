package io.novafoundation.nova.feature_dapp_impl.presentation.browser.main.kurd

import io.novafoundation.nova.runtime.names.KurdName
import io.novafoundation.nova.runtime.names.KurdResolution
import io.novafoundation.nova.runtime.names.NameRecord
import io.novafoundation.nova.runtime.names.NameStatus
import io.novafoundation.nova.runtime.names.NameSuspension
import io.novafoundation.nova.runtime.names.content.Cid
import io.novafoundation.nova.runtime.names.content.KurdSiteServer.PageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KurdPageUiTest {

    private val name = KurdName.parse("dks.kurd")!!
    private val root = Cid.parse(byteArrayOf(0x01, 0x55, 0x12, 0x20) + ByteArray(32) { 7 })

    @Test
    fun `the badge says what was verified`() {
        assertEquals(KurdPageUi.Badge.OFFICIAL, badgeOf(granted(), verified = true))
        assertEquals(KurdPageUi.Badge.REGISTERED, badgeOf(leased(), verified = true))
        // An unverified name never shows as official, whatever its record claims
        assertEquals(KurdPageUi.Badge.UNVERIFIED, badgeOf(granted(), verified = false))
    }

    @Test
    fun `a block is shown only for a proof`() {
        val proven = open(leased(), verified = true, block = 1_284_551)
        val reported = open(leased(), verified = false, block = 1_284_551)

        assertEquals(1_284_551L, proven.details.provenAtBlock)
        assertNull(reported.details.provenAtBlock)
    }

    @Test
    fun `an office or Parliament owns as an institution, a person as an account`() {
        assertTrue(open(granted(), verified = true).details.owner is KurdPageUi.Owner.Institution)
        assertTrue(open(leased(), verified = true).details.owner is KurdPageUi.Owner.Account)
    }

    @Test
    fun `each state that is not a page is a notice`() {
        val resolved = KurdResolution.Resolved(name, leased(), NameStatus.ACTIVE, verified = false)

        assertEquals(KurdPageUi.NoticeKind.NEEDS_CONSENT, noticeOf(PageState.NeedsConsent(resolved)))
        assertEquals(KurdPageUi.NoticeKind.REFUSED, noticeOf(PageState.Refused))
        assertEquals(KurdPageUi.NoticeKind.NOT_REGISTERED, noticeOf(PageState.NotRegistered))
        assertEquals(KurdPageUi.NoticeKind.NO_SITE, noticeOf(PageState.NoSite))
        assertEquals(KurdPageUi.NoticeKind.UNAVAILABLE, noticeOf(PageState.Unavailable))
        assertNull(KurdPageUi.from(name, null))
    }

    @Test
    fun `a suspended name is a notice that carries who stopped it and until when`() {
        val suspension = NameSuspension(until = 1_800_000_000_000, reg = 1, reason = "0x00", by = NameSuspension.By.EMERGENCY)
        val notice = KurdPageUi.from(name, PageState.Suspended(suspension)) as KurdPageUi.Notice

        assertEquals(KurdPageUi.NoticeKind.SUSPENDED, notice.kind)
        assertEquals(suspension, notice.suspension)
    }

    @Test
    fun `a feed showing the chain's version says so`() {
        val resolution = KurdResolution.Resolved(name, leased(), NameStatus.ACTIVE, verified = true)

        assertTrue((KurdPageUi.from(name, PageState.Open(resolution, root, onChainVersion = true)) as KurdPageUi.Open).onChainVersion)
        assertTrue(!(KurdPageUi.from(name, PageState.Open(resolution, root)) as KurdPageUi.Open).onChainVersion)
    }

    @Test
    fun `only pages on a kurd host have a name`() {
        assertEquals("serok.gov.kurd", KurdPageUi.nameOf("https://serok.gov.kurd/news?x=1")!!.name)
        assertNull(KurdPageUi.nameOf("https://dks.kurd.example.com/"))
        assertNull(KurdPageUi.nameOf("https://app.pezkuwichain.io/"))
        assertNull(KurdPageUi.nameOf(null))
    }

    private fun badgeOf(record: NameRecord, verified: Boolean) = open(record, verified).badge

    private fun open(record: NameRecord, verified: Boolean, block: Long? = null): KurdPageUi.Open {
        val resolution = KurdResolution.Resolved(name, record, NameStatus.ACTIVE, verified, block)
        return KurdPageUi.from(name, PageState.Open(resolution, root)) as KurdPageUi.Open
    }

    private fun noticeOf(state: PageState) = (KurdPageUi.from(name, state) as KurdPageUi.Notice).kind

    private fun granted() = record(NameRecord.Owner.Governance, NameRecord.Kind.GRANTED, locked = true)

    private fun leased() = record(NameRecord.Owner.Account(ByteArray(32)), NameRecord.Kind.LEASED, locked = false)

    private fun record(owner: NameRecord.Owner, kind: NameRecord.Kind, locked: Boolean) = NameRecord(
        label = "dks", parent = KurdName.KURD_NODE, parentReg = 0, reg = 1, owner = owner, kind = kind,
        lockedTransfer = locked, expiry = null, account = null, content = null,
    )
}
