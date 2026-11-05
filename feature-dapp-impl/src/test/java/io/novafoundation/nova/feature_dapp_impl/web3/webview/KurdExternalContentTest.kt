package io.novafoundation.nova.feature_dapp_impl.web3.webview

import io.novafoundation.nova.feature_dapp_impl.web3.webview.KurdExternalContent.Kind
import io.novafoundation.nova.feature_dapp_impl.web3.webview.KurdExternalContent.kindOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KurdExternalContentTest {

    private fun report(directive: String, uri: String) = """{"directive":"$directive","uri":"$uri"}"""

    @Test
    fun `a server elsewhere is reported by what it would have served`() {
        assertEquals(Kind.MEDIA, kindOf(report("media-src", "https://video.example/a.mp4"), "https://dks-news.kurd", isMainFrame = true))
        assertEquals(Kind.IMAGE, kindOf(report("img-src", "https://cdn.example/x.png"), "https://dks-news.kurd", isMainFrame = true))
        assertEquals(Kind.OTHER, kindOf(report("connect-src", "wss://feed.example/"), "https://dks-news.kurd", isMainFrame = true))
        assertEquals(Kind.OTHER, kindOf(report("script-src-elem", "https://cdn.example/a.js"), "https://serok.gov.kurd", isMainFrame = true))
    }

    @Test
    fun `another kurd name is a server elsewhere too`() {
        assertEquals(Kind.IMAGE, kindOf(report("img-src", "https://other.kurd/x.png"), "https://dks.kurd", isMainFrame = true))
    }

    @Test
    fun `a block that is not about a server is not`() {
        // Inline script, eval and data: URLs are the page's own doing, not a server it relies on
        assertNull(kindOf(report("script-src-elem", "inline"), "https://dks.kurd", isMainFrame = true))
        assertNull(kindOf(report("script-src", "eval"), "https://dks.kurd", isMainFrame = true))
        assertNull(kindOf(report("img-src", "data"), "https://dks.kurd", isMainFrame = true))
        assertNull(kindOf(report("img-src", "https://dks.kurd/x.png"), "https://dks.kurd", isMainFrame = true))
    }

    @Test
    fun `only a kurd top frame is heard`() {
        assertNull(kindOf(report("img-src", "https://cdn.example/x.png"), "https://dks.kurd", isMainFrame = false))
        assertNull(kindOf(report("img-src", "https://cdn.example/x.png"), "https://app.pezkuwichain.io", isMainFrame = true))
        assertNull(kindOf(report("img-src", "https://cdn.example/x.png"), "https://dks.kurd.example", isMainFrame = true))
    }

    @Test
    fun `a malformed report is dropped`() {
        assertNull(kindOf("not json", "https://dks.kurd", isMainFrame = true))
        assertNull(kindOf("""{"directive":"img-src"}""", "https://dks.kurd", isMainFrame = true))
        assertNull(kindOf("""{"uri":"https://cdn.example/"}""", "https://dks.kurd", isMainFrame = true))
    }
}
