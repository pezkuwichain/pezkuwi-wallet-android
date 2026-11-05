package io.novafoundation.nova.feature_dapp_impl.utils.tabs.models

import io.novafoundation.nova.runtime.names.content.KurdSiteServer
import android.graphics.Bitmap
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebResourceRequest
import android.webkit.WebView
import io.novafoundation.nova.common.resources.ContextManager
import io.novafoundation.nova.feature_dapp_impl.utils.integrityCheck.IntegrityCheckJSBridge
import io.novafoundation.nova.feature_dapp_impl.utils.integrityCheck.IntegrityCheckJSBridgeFactory
import io.novafoundation.nova.feature_dapp_impl.web3.webview.PageCallback
import io.novafoundation.nova.feature_dapp_impl.web3.webview.Web3ChromeClient
import io.novafoundation.nova.feature_dapp_impl.web3.webview.CompoundWeb3Injector
import io.novafoundation.nova.feature_dapp_impl.web3.webview.KurdExternalContent
import io.novafoundation.nova.feature_dapp_impl.web3.webview.WebViewWeb3JavaScriptInterface
import io.novafoundation.nova.feature_dapp_impl.web3.webview.Web3WebViewClient
import io.novafoundation.nova.feature_dapp_impl.web3.webview.injectWeb3
import io.novafoundation.nova.feature_dapp_impl.web3.webview.uninjectWeb3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class BrowserTabSessionFactory(
    private val compoundWeb3Injector: CompoundWeb3Injector,
    private val contextManager: ContextManager,
    private val integrityCheckJSBridgeFactory: IntegrityCheckJSBridgeFactory,
    private val kurdSiteServer: KurdSiteServer,
) {

    suspend fun create(tabId: String, startUrl: String, onPageChangedCallback: OnPageChangedCallback): BrowserTabSession {
        return withContext(Dispatchers.Main) {
            val context = contextManager.getActivity()!!
            val webView = WebView(context)
            val integrityCheckProvider = integrityCheckJSBridgeFactory.create(webView)

            BrowserTabSession(
                tabId = tabId,
                startUrl = startUrl,
                webView = webView,
                integrityCheckJSBridge = integrityCheckProvider,
                compoundWeb3Injector = compoundWeb3Injector,
                onPageChangedCallback = onPageChangedCallback,
                kurdSiteServer = kurdSiteServer,
            )
        }
    }
}

class BrowserTabSession(
    val tabId: String,
    val startUrl: String,
    val webView: WebView,
    compoundWeb3Injector: CompoundWeb3Injector,
    private val onPageChangedCallback: OnPageChangedCallback,
    private val integrityCheckJSBridge: IntegrityCheckJSBridge,
    kurdSiteServer: KurdSiteServer,
) : PageCallback, CoroutineScope by CoroutineScope(Dispatchers.Main) {

    val webViewClient: Web3WebViewClient = Web3WebViewClient(
        webView = webView,
        pageCallback = this,
        kurdSiteServer = kurdSiteServer,
    )

    var currentUrl: String = startUrl

    private var sessionCallback: SessionCallback? = null

    private val _kurdBlocked = MutableStateFlow<Set<KurdExternalContent.Kind>>(emptySet())

    /** What the `.kurd` document shown now could not load from servers outside its name. */
    val kurdBlocked: StateFlow<Set<KurdExternalContent.Kind>> = _kurdBlocked

    init {
        webView.injectWeb3(webViewClient)
        listenForKurdBlockedContent()
        // Before the first load: the bridge is only exposed to documents loaded after it is added
        compoundWeb3Injector.initialInject(webView)
        webView.loadUrl(startUrl)

        integrityCheckJSBridge.errorFlow
            .onEach { sessionCallback?.onPageError(it) }
            .launchIn(this)
    }

    fun attachToHost(
        chromeClient: Web3ChromeClient,
        pageCallback: SessionCallback
    ) {
        webView.webChromeClient = chromeClient
        this.sessionCallback = pageCallback

        // To provide initial state
        pageCallback.onPageChanged(webView, webView.url, webView.title)
    }

    fun detachFromHost() {
        sessionCallback = null
        webView.webChromeClient = null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        _kurdBlocked.value = emptySet()
        sessionCallback?.onPageStarted(view, url, favicon)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return sessionCallback?.shouldOverrideUrlLoading(view, request) ?: false
    }

    override fun onPageChanged(view: WebView, url: String?, title: String?) {
        sessionCallback?.onPageChanged(view, url, title)
        onPageChangedCallback.onPageChanged(tabId, url, title)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        integrityCheckJSBridge.onPageFinished()
    }

    /** Without both features the note is simply never shown: the content stays blocked either way. */
    private fun listenForKurdBlockedContent() {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!supported) return

        WebViewCompat.addWebMessageListener(webView, KurdExternalContent.LISTENER_NAME, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
            // A report still in flight from the page before must not land on the one shown now
            if (!WebViewWeb3JavaScriptInterface.sameOrigin(sourceOrigin.toString(), webView.url.orEmpty())) return@addWebMessageListener
            val kind = message.data?.let { KurdExternalContent.kindOf(it, sourceOrigin.toString(), isMainFrame) } ?: return@addWebMessageListener
            _kurdBlocked.value = _kurdBlocked.value + kind
        }
        WebViewCompat.addDocumentStartJavaScript(webView, KurdExternalContent.DOCUMENT_START_SCRIPT, setOf("*"))
    }

    fun destroy() {
        cancel() // Cancel coroutine scope
        integrityCheckJSBridge.onDestroy()
        webView.uninjectWeb3()
        webView.destroy()
    }
}
