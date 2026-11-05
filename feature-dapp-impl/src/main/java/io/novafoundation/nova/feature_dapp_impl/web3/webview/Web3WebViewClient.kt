package io.novafoundation.nova.feature_dapp_impl.web3.webview

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.novafoundation.nova.runtime.names.KurdName
import java.io.ByteArrayInputStream

interface PageCallback {

    fun onPageStarted(webView: WebView, url: String, favicon: Bitmap?)

    fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean

    fun onPageChanged(webView: WebView, url: String?, title: String?)

    fun onPageFinished(view: WebView, url: String?)
}

class Web3WebViewClient(
    private val webView: WebView,
    private val pageCallback: PageCallback
) : WebViewClient() {

    var desktopMode: Boolean = false
        set(value) {
            if (value) {
                setDesktopViewport(webView)
            }
            desktopModeChanged = field != value
            field = value
        }
    private var desktopModeChanged = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (KurdName.isKurdHost(request.url.host)) return true

        if (pageCallback.shouldOverrideUrlLoading(view, request)) {
            return true
        }

        return super.shouldOverrideUrlLoading(view, request)
    }

    /**
     * Every request to a `.kurd` host -- a page, a frame, an image a page embeds -- ends here, before
     * the network: such a host is a name on chain, and asking system DNS for it would tell the
     * network which site the reader opens. `loadUrl` reaches this hook too, unlike
     * [shouldOverrideUrlLoading].
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!KurdName.isKurdHost(request.url.host)) return null

        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageCallback.onPageStarted(view, url, favicon)
        if (desktopMode) {
            setDesktopViewport(view)
        }
        if (desktopModeChanged) {
            webView.changeUserAgentByDesktopMode(desktopMode)
            desktopModeChanged = false
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        pageCallback.onPageFinished(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        pageCallback.onPageChanged(view, url, view.title)
    }

    private fun setDesktopViewport(webView: WebView) {
        val density = webView.context.resources.displayMetrics.density
        val deviceWidth = webView.measuredWidth
        val scale = (deviceWidth / density) / 1100
        webView.evaluateJavascript(
            "document.querySelector('meta[name=\"viewport\"]').setAttribute('content', 'width=device-width, initial-scale=$scale');",
            null
        )
    }
}

private fun WebView.changeUserAgentByDesktopMode(desktopMode: Boolean) {
    val defaultUserAgent = WebSettings.getDefaultUserAgent(context)

    settings.userAgentString = if (desktopMode) {
        "Mozilla/5.0 (X11; CrOS x86_64 10066.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    } else {
        defaultUserAgent
    }
}
