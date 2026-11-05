package io.novafoundation.nova.feature_dapp_impl.web3.webview

import io.novafoundation.nova.feature_dapp_impl.web3.webview.WebViewWeb3JavaScriptInterface.Companion.isFromShownPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewWeb3JavaScriptInterfaceTest {

    @Test
    fun `the top frame of the shown page is heard`() {
        assertTrue(isFromShownPage("https://app.pezkuwichain.io/staking?x=1", "https://app.pezkuwichain.io", isMainFrame = true))
        assertTrue(isFromShownPage("https://dks.kurd/", "https://dks.kurd", isMainFrame = true))
        assertTrue(isFromShownPage("https://App.Example:443/a", "https://app.example", isMainFrame = true))
    }

    @Test
    fun `a frame inside the page is not, even from the same origin`() {
        assertFalse(isFromShownPage("https://app.pezkuwichain.io/", "https://ads.example", isMainFrame = false))
        assertFalse(isFromShownPage("https://app.pezkuwichain.io/", "https://app.pezkuwichain.io", isMainFrame = false))
    }

    @Test
    fun `a message from another origin than the page shown is not`() {
        // A page still posting while the tab moves to an authorised DApp
        assertFalse(isFromShownPage("https://trusted.example/", "https://evil.example", isMainFrame = true))
        assertFalse(isFromShownPage("https://trusted.example/", "http://trusted.example", isMainFrame = true))
        assertFalse(isFromShownPage("https://trusted.example/", "https://trusted.example:8443", isMainFrame = true))
        assertFalse(isFromShownPage("https://trusted.example.evil.example/", "https://trusted.example", isMainFrame = true))
    }

    @Test
    fun `no page or an opaque origin is not`() {
        assertFalse(isFromShownPage(null, "https://dks.kurd", isMainFrame = true))
        assertFalse(isFromShownPage("about:blank", "null", isMainFrame = true))
        assertFalse(isFromShownPage("data:text/html,hi", "null", isMainFrame = true))
    }
}
