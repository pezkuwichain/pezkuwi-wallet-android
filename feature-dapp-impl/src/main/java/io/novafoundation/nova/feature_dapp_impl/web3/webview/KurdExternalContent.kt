package io.novafoundation.nova.feature_dapp_impl.web3.webview

import com.google.gson.JsonParser
import io.novafoundation.nova.runtime.names.KurdName
import java.net.URI

/**
 * What a `.kurd` page tried to load from outside its own name. The wallet's policy lets such a page
 * load only from itself (`KurdSiteServer.CONTENT_SECURITY_POLICY`), so a site built to fetch a video
 * or a font from a server elsewhere has that part missing. Reported to the reader, so a hole in the
 * page has a reason.
 */
object KurdExternalContent {

    enum class Kind { MEDIA, IMAGE, OTHER }

    /** The page global the document-start script reports through. */
    const val LISTENER_NAME = "Pezkuwi_kurdBlocked"

    /**
     * Runs at the start of every document, before the page's own scripts, and is not subject to the
     * page's policy. Only a `.kurd` document listens; each directive is reported once per server.
     */
    val DOCUMENT_START_SCRIPT = """
        (function () {
          var host = location.hostname.toLowerCase();
          if (host !== 'kurd' && host.slice(-5) !== '.kurd') return;
          var listener = window.$LISTENER_NAME;
          if (!listener) return;
          var seen = {};
          document.addEventListener('securitypolicyviolation', function (event) {
            var key = event.effectiveDirective + ' ' + event.blockedURI.split('/').slice(0, 3).join('/');
            if (seen[key]) return;
            seen[key] = true;
            listener.postMessage(JSON.stringify({ directive: event.effectiveDirective, uri: event.blockedURI }));
          });
        })();
    """.trimIndent()

    /**
     * The kind of content a violation report from [sourceOrigin] says was blocked, or null when it is
     * not a `.kurd` top frame reporting a server other than its own (inline script, `eval`, a
     * malformed report). A page can send this itself, but only to have a note shown about itself.
     */
    fun kindOf(message: String, sourceOrigin: String, isMainFrame: Boolean): Kind? = runCatching {
        if (!isMainFrame) return null
        val pageHost = URI(sourceOrigin).host?.lowercase() ?: return null
        if (!KurdName.isKurdHost(pageHost)) return null

        val report = JsonParser.parseString(message).asJsonObject
        val directive = report.get("directive")?.asString ?: return null
        val blocked = URI(report.get("uri")?.asString ?: return null)
        // `inline`, `eval`, `data` and the like carry no host
        val blockedHost = blocked.host?.lowercase() ?: return null
        if (blockedHost == pageHost) return null

        when (directive) {
            "media-src" -> Kind.MEDIA
            "img-src" -> Kind.IMAGE
            else -> Kind.OTHER
        }
    }.getOrNull()
}
