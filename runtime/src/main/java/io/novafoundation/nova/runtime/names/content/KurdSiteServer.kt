package io.novafoundation.nova.runtime.names.content

import io.novafoundation.nova.runtime.names.KurdName
import io.novafoundation.nova.runtime.names.KurdNameResolver
import io.novafoundation.nova.runtime.names.KurdResolution
import io.novafoundation.nova.runtime.names.NameRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the DApp browser's requests to `https://<name>.kurd/...`: the name is resolved on chain,
 * its content root fetched from gateways and verified, and only then served. The page's origin is
 * the full name, so cookies, storage and permissions belong to that name alone.
 */
class KurdSiteServer(
    private val resolver: KurdNameResolver,
    private val fetcher: KurdContentFetcher,
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

    private val resolutions = ConcurrentHashMap<KurdName, Pair<Long, KurdResolution>>()

    /** The latest resolution of [name], for the browser to show how it was verified. */
    fun lastResolution(name: KurdName): KurdResolution? = resolutions[name]?.second

    suspend fun serve(host: String, encodedPath: String): Response {
        val name = KurdName.parse(host) ?: return status(404, "Not Found")
        val segments = decodePath(encodedPath) ?: return status(400, "Bad Request")

        val resolution = resolve(name)
        val record = when (resolution) {
            is KurdResolution.Resolved -> resolution.record
            is KurdResolution.NotFound -> return status(404, "Not Found")
            is KurdResolution.Refused -> return status(403, "Forbidden")
            is KurdResolution.Unavailable -> return status(502, "Bad Gateway")
        }

        val root = when (val content = record.content) {
            null -> return status(404, "Not Found")
            is NameRecord.Content.Ipfs -> Cid.fromContenthash(content.contenthash) ?: return status(415, "Unsupported Media Type")
            // A feed is resolved through its signed pointer, which this server does not read
            is NameRecord.Content.Feed -> return status(415, "Unsupported Media Type")
        }

        return when (val result = fetcher.fetch(root, segments)) {
            is KurdContentFetcher.Result.File -> Response(200, "OK", mimeTypeOf(result.path.lastOrNull()), result.bytes)
            KurdContentFetcher.Result.NotFound -> status(404, "Not Found")
            KurdContentFetcher.Result.Unavailable -> status(502, "Bad Gateway")
        }
    }

    private suspend fun resolve(name: KurdName): KurdResolution {
        val now = clock()
        resolutions[name]?.let { (at, resolution) -> if (now - at < resolutionTtlMillis) return resolution }
        return resolver.resolve(name).also { resolutions[name] = now to it }
    }

    private fun status(code: Int, reason: String) = Response(code, reason, "text/plain", ByteArray(0))

    companion object {

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
