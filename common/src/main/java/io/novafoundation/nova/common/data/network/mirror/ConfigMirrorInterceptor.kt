package io.novafoundation.nova.common.data.network.mirror

import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Every config file the app reads (chains, assets, DApps, XCM, banners, icons) lives on
 * raw.githubusercontent.com. When that host cannot be reached -- blocked, down, rate limited -- a fresh
 * install has nothing to show. This interceptor retries the same file on mirrors that serve the same
 * repository content, in order, and remembers for [hostDownMillis] that the primary failed so the next
 * requests do not each wait for its timeout first.
 *
 * A 404 is an answer, not an outage: it is returned as is and no mirror is asked.
 */
class ConfigMirrorInterceptor(
    private val mirrors: List<GithubMirror> = DEFAULT_MIRRORS,
    private val hostDownMillis: Long = TimeUnit.MINUTES.toMillis(10),
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {

    companion object {
        const val GITHUB_RAW_HOST = "raw.githubusercontent.com"

        val DEFAULT_MIRRORS = listOf(
            GithubMirror.JsDelivr("cdn.jsdelivr.net"),
            GithubMirror.JsDelivr("fastly.jsdelivr.net"),
        )

        private val OUTAGE_CODES = setOf(403, 429, 451)
    }

    private val downUntil = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val file = GithubFile.parse(request.url)

        if (file == null || request.method != "GET") return chain.proceed(request)

        val candidates = listOf(request) + mirrors.map { mirror ->
            request.newBuilder()
                .url(mirror.urlOf(file).newBuilder().encodedQuery(request.url.encodedQuery).build())
                // A mirror's max-age would pin its copy in the HTTP cache long after the primary is back.
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()
        }

        val available = candidates.filterNot { isDown(it.url.host) }.ifEmpty { candidates }

        var lastFailure: IOException? = null
        var lastOutage: Response? = null

        for ((index, candidate) in available.withIndex()) {
            val isLast = index == available.lastIndex

            val response = try {
                chain.proceed(candidate)
            } catch (e: IOException) {
                markDown(candidate.url.host)
                lastFailure = e
                continue
            }

            if (!isOutage(response) || isLast) {
                if (!isOutage(response)) downUntil.remove(candidate.url.host)
                lastOutage?.close()
                return response
            }

            markDown(candidate.url.host)
            lastOutage?.close()
            lastOutage = response
        }

        lastOutage?.let { return it }
        throw lastFailure ?: IOException("No source answered for ${request.url}")
    }

    private fun isOutage(response: Response) = response.code in OUTAGE_CODES || response.code >= 500

    private fun isDown(host: String): Boolean {
        val until = downUntil[host] ?: return false
        if (clock() < until) return true
        downUntil.remove(host, until)
        return false
    }

    private fun markDown(host: String) {
        downUntil[host] = clock() + hostDownMillis
    }
}

/** A file in a GitHub repository at a ref, as raw.githubusercontent.com addresses it. */
data class GithubFile(val owner: String, val repo: String, val ref: String, val path: String) {

    companion object {

        fun parse(url: HttpUrl): GithubFile? {
            if (url.host != ConfigMirrorInterceptor.GITHUB_RAW_HOST) return null

            val segments = url.pathSegments
            if (segments.size < 4) return null

            val (owner, repo) = segments
            // raw accepts both `master` and `refs/heads/master`; mirrors only know the short form.
            val refLength = if (segments[2] == "refs" && segments.size >= 6) 3 else 1
            val ref = segments.subList(2, 2 + refLength).last()
            val path = segments.drop(2 + refLength).joinToString("/")

            if (path.isEmpty()) return null

            return GithubFile(owner, repo, ref, path)
        }
    }
}

sealed interface GithubMirror {

    fun urlOf(file: GithubFile): HttpUrl

    /** jsDelivr serves any public GitHub repository at `/gh/{owner}/{repo}@{ref}/{path}`. */
    class JsDelivr(private val host: String) : GithubMirror {

        override fun urlOf(file: GithubFile): HttpUrl {
            return "https://$host/gh/${file.owner}/${file.repo}@${file.ref}/${file.path}".toHttpUrl()
        }
    }
}
