package io.novafoundation.nova.feature_dapp_impl.web3.webview

import android.util.Log
import android.webkit.WebView
import androidx.annotation.RawRes
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.utils.LOG_TAG

private const val JAVASCRIPT_INTERFACE_PREFIX = "Pezkuwi"

// The name a page's scripts call the native interface by; bundled bridge scripts must use this exact name.
internal fun jsInterfaceFullName(interfaceName: String) = "${JAVASCRIPT_INTERFACE_PREFIX}_$interfaceName"

class WebViewScriptInjector(
    private val resourceManager: ResourceManager
) {

    private enum class InjectionPosition {
        START, END
    }

    private val scriptCache: MutableMap<Int, String> = mutableMapOf()

    /**
     * Exposes `postMessage` to page scripts under the interface name. Through a web message listener
     * rather than `addJavascriptInterface`, so each message arrives with its origin and frame; on a
     * WebView too old to say, the bridge stays off rather than trusting the page.
     */
    fun injectJsInterface(
        into: WebView,
        jsInterface: WebViewWeb3JavaScriptInterface,
        interfaceName: String
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(LOG_TAG, "WebView cannot tell where a message came from; DApp bridge $interfaceName stays off")
            return
        }

        WebViewCompat.addWebMessageListener(into, jsInterfaceFullName(interfaceName), setOf("*")) { view, message, sourceOrigin, isMainFrame, _ ->
            message.data?.let { jsInterface.onMessage(view, it, sourceOrigin, isMainFrame) }
        }
    }

    fun injectScript(
        scriptContent: String,
        into: WebView,
        scriptId: String = scriptContent.hashCode().toString(),
    ) {
        addScriptToDomIfNotExists(scriptContent, scriptId, into)
    }

    fun injectScript(
        @RawRes scriptRes: Int,
        into: WebView,
        scriptId: String = scriptRes.toString()
    ) {
        val script = loadScript(scriptRes)

        addScriptToDomIfNotExists(script, scriptId, into)
    }

    private fun loadScript(@RawRes scriptRes: Int) = scriptCache.getOrPut(scriptRes) {
        resourceManager.loadRawString(scriptRes)
    }

    private fun addScriptToDomIfNotExists(
        js: String,
        scriptId: String,
        into: WebView,
    ) {
        val wrappedScript = """
            (function() {
               $js
            })();
        """.trimIndent()

        into.evaluateJavascript(wrappedScript, null)
    }

    private val InjectionPosition.addMethodName
        get() = when (this) {
            InjectionPosition.START -> "prepend"
            InjectionPosition.END -> "appendChild"
        }
}
