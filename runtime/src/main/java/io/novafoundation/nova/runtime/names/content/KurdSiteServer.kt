package io.novafoundation.nova.runtime.names.content

import io.novafoundation.nova.runtime.names.KurdName
import io.novafoundation.nova.runtime.names.KurdNameResolver
import io.novafoundation.nova.runtime.names.KurdResolution
import io.novafoundation.nova.runtime.names.NameRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the DApp browser's requests to `https://<name>.kurd/...`: the name is resolved on chain,
 * its content root fetched from gateways and verified, and only then served. The page's origin is
 * the full name, so cookies, storage and permissions belong to that name alone.
 */
class KurdSiteServer(
    private val resolver: KurdNameResolver,
    private val fetcher: KurdContentFetcher,
    private val feeds: KurdFeedResolver? = null,
    private val resolutionTtlMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    class Response(val status: Int, val reason: String, val mimeType: String, val body: ByteArray) {

        /** The policy the wallet writes for every `.kurd` page: nothing loads or connects outside the name. */
        val headers: Map<String, String> = mapOf(
            "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cache-Control" to "no-store",
        )
    }

    /** How the browser shows a `.kurd` page: set by its top-level document, read by the address bar and notices. */
    sealed interface PageState {

        /**
         * Content verified and served. [resolution] says how the name itself was read; for a feed,
         * [onChainVersion] means no gateway confirmed a release past the one the chain names.
         */
        class Open(val resolution: KurdResolution.Resolved, val root: Cid, val onChainVersion: Boolean = false) : PageState

        /** Read through RPC nodes only; the content waits until the reader chooses to open it. */
        class NeedsConsent(val resolution: KurdResolution.Resolved) : PageState

        /** Official or institutional and not verifiable now: never opened (decision D18). */
        object Refused : PageState

        object NotRegistered : PageState

        /** Registered, but no site: no content, or content of a kind this wallet does not serve. */
        object NoSite : PageState

        object Unavailable : PageState
    }

    private val resolutions = ConcurrentHashMap<KurdName, Pair<Long, KurdResolution>>()
    private val consented: MutableSet<KurdName> = ConcurrentHashMap.newKeySet()

    private val _pageStates = MutableStateFlow<Map<KurdName, PageState>>(emptyMap())
    val pageStates: StateFlow<Map<KurdName, PageState>> = _pageStates

    /**
     * The reader chose to open [name] although only RPC nodes vouched for it; holds until the app
     * restarts. Never offered for a name that must be verified, which is [PageState.Refused].
     */
    fun allowUnverified(name: KurdName) {
        consented += name
        resolutions -= name
    }

    /** Forgets [name]'s resolution, so a retry reads the chain again. */
    fun forget(name: KurdName) {
        resolutions -= name
    }

    /** [mainFrame]: the page's own document, whose outcome becomes the name's [PageState]. */
    suspend fun serve(host: String, encodedPath: String, mainFrame: Boolean = false): Response {
        val name = KurdName.parse(host) ?: return status(404, "Not Found")
        val segments = decodePath(encodedPath) ?: return status(400, "Bad Request")

        val (state, response) = handle(name, segments, mainFrame)
        if (mainFrame) _pageStates.update { it + (name to state) }
        return response
    }

    private suspend fun handle(name: KurdName, segments: List<String>, mainFrame: Boolean): Pair<PageState, Response> {
        val resolution = when (val resolved = resolve(name)) {
            is KurdResolution.Resolved -> resolved
            is KurdResolution.NotFound -> return PageState.NotRegistered to status(404, "Not Found")
            is KurdResolution.Refused -> return PageState.Refused to status(403, "Forbidden")
            is KurdResolution.Unavailable -> return PageState.Unavailable to status(502, "Bad Gateway")
        }

        if (resolution.record.content == null) return PageState.NoSite to status(404, "Not Found")
        if (!resolution.verified && name !in consented) return PageState.NeedsConsent(resolution) to status(428, "Precondition Required")

        val (root, onChainVersion) = when (val content = resolution.record.content) {
            is NameRecord.Content.Ipfs -> {
                val cid = Cid.fromContenthash(content.contenthash) ?: return PageState.NoSite to status(415, "Unsupported Media Type")
                cid to false
            }
            is NameRecord.Content.Feed -> {
                val release = feeds?.resolve(content) ?: return PageState.NoSite to status(415, "Unsupported Media Type")
                release.root to release.onChainVersion
            }
            null -> return PageState.NoSite to status(404, "Not Found")
        }

        val open = PageState.Open(resolution, root, onChainVersion)
        return when (val result = fetcher.fetch(root, segments)) {
            is KurdContentFetcher.Result.File -> open to Response(200, "OK", mimeTypeOf(result.path.lastOrNull()), result.bytes)
            KurdContentFetcher.Result.NotFound -> open to if (mainFrame) notFoundPage(root) else status(404, "Not Found")
            KurdContentFetcher.Result.Unavailable -> PageState.Unavailable to status(502, "Bad Gateway")
        }
    }

    /**
     * A page the site does not have: its own `/404.html` when it ships one, as a web server would
     * answer, and otherwise a line of text. Never an empty body, which WebView replaces with its
     * own error screen as if the site could not be reached.
     */
    private suspend fun notFoundPage(root: Cid): Response {
        val page = fetcher.fetch(root, listOf(NOT_FOUND_PAGE)) as? KurdContentFetcher.Result.File
            ?: return Response(404, "Not Found", "text/plain", "404 Not Found".toByteArray())

        return Response(404, "Not Found", mimeTypeOf(NOT_FOUND_PAGE), page.bytes)
    }

    private suspend fun resolve(name: KurdName): KurdResolution {
        val now = clock()
        resolutions[name]?.let { (at, resolution) -> if (now - at < resolutionTtlMillis) return resolution }
        return resolver.resolve(name).also { resolutions[name] = now to it }
    }

    private fun status(code: Int, reason: String) = Response(code, reason, "text/plain", ByteArray(0))

    companion object {

        private const val NOT_FOUND_PAGE = "404.html"

        const val CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; media-src 'self' blob:; font-src 'self' data:; connect-src 'self'; " +
            "worker-src 'none'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"

        /**
         * Path segments, percent-decoded one by one. Null for a malformed escape, a non-UTF-8 byte
         * sequence, or a segment that is `.`, `..` or holds an encoded `/`: none names a file.
         */
        fun decodePath(encodedPath: String): List<String>? {
            val segments = encodedPath.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
            return segments.map { encoded ->
                percentDecode(encoded)?.takeIf { it != "." && it != ".." && '/' !in it && it.isNotEmpty() } ?: return null
            }
        }

        private fun percentDecode(segment: String): String? {
            val out = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < segment.length) {
                val char = segment[index]
                if (char == '%') {
                    if (index + 3 > segment.length) return null
                    val high = hexValue(segment[index + 1])
                    val low = hexValue(segment[index + 2])
                    if (high < 0 || low < 0) return null
                    out.write(high * 16 + low)
                    index += 3
                } else {
                    out.write(char.toString().toByteArray(Charsets.UTF_8))
                    index++
                }
            }
            val bytes = out.toByteArray()
            val decoder = Charsets.UTF_8.newDecoder()
            return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        }

        /** ASCII hex only: `Character.digit` would also take other scripts' digits. */
        private fun hexValue(char: Char): Int = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> -1
        }

        private val MIME_TYPES = mapOf(
            "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript",
            "json" to "application/json", "map" to "application/json", "txt" to "text/plain", "xml" to "application/xml",
            "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "avif" to "image/avif", "ico" to "image/x-icon", "woff" to "font/woff", "woff2" to "font/woff2",
            "ttf" to "font/ttf", "otf" to "font/otf", "wasm" to "application/wasm", "mp3" to "audio/mpeg", "ogg" to "audio/ogg",
            "mp4" to "video/mp4", "webm" to "video/webm", "pdf" to "application/pdf",
        )

        fun mimeTypeOf(fileName: String?): String {
            val extension = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
            return MIME_TYPES[extension] ?: "application/octet-stream"
        }
    }
}
