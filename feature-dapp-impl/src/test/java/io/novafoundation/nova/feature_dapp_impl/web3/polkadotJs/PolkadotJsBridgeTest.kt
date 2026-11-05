package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs

import io.novafoundation.nova.feature_dapp_impl.web3.webview.jsInterfaceFullName
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolkadotJsBridgeTest {

    // A bridge calling a name the WebView never registered throws inside the page's message listener,
    // so every polkadot-js request (enable, accounts, signing) is dropped without a word.
    @Test
    fun `bridge script calls the interface the injector registers`() {
        val bridge = File("src/main/res/raw/javascript_interface_bridge.js").readText()

        val call = "${jsInterfaceFullName(POLKADOT_JS_INTERFACE_NAME)}.onNewMessage("
        assertTrue("bridge must call $call", bridge.contains(call))
    }
}
