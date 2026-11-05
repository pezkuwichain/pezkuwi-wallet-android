package io.novafoundation.nova.feature_dapp_impl.web3.webview

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import io.novafoundation.nova.common.utils.LOG_TAG
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.URI

/**
 * Receives what the bundled bridge scripts post. The WebView, not the page, says where a message
 * came from: only the top frame of the page the tab is showing is heard, so a third-party frame
 * inside an authorised DApp cannot ask for accounts or signatures in that DApp's name.
 */
class WebViewWeb3JavaScriptInterface {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val messages: Flow<String> = _messages

    /** Called on the main thread, with the sender's origin and frame as the WebView knows them. */
    fun onMessage(view: WebView, message: String, sourceOrigin: Uri, isMainFrame: Boolean) {
        if (!isFromShownPage(view.url, sourceOrigin.toString(), isMainFrame)) {
            Log.w(LOG_TAG, "Ignoring a bridge message from $sourceOrigin, main frame $isMainFrame, page ${view.url}")
            return
        }

        _messages.tryEmit(message)
    }

    companion object {

        /** True for a message from the top frame whose origin is the origin of [pageUrl]. */
        fun isFromShownPage(pageUrl: String?, sourceOrigin: String, isMainFrame: Boolean): Boolean {
            if (!isMainFrame) return false
            val page = originOf(pageUrl ?: return false) ?: return false
            return page == originOf(sourceOrigin)
        }

        /** `scheme://host[:port]`, lower-cased, default ports dropped; null for an opaque or host-less URL. */
        internal fun originOf(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
            return "$scheme://$host" + (port?.let { ":$it" } ?: "")
        }
    }
}
