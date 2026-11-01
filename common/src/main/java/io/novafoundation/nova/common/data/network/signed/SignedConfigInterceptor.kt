package io.novafoundation.nova.common.data.network.signed

import io.novafoundation.nova.common.data.network.mirror.ConfigMirrorInterceptor
import io.novafoundation.nova.common.data.network.mirror.GithubFile
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the wallet has trusted so far; it outlives the process so a replayed older list is still refused. */
interface SignedConfigStore {

    /** The newest root accepted after the bundled one, as its envelope bytes; null while the bundled root stands. */
    var trustedRoot: ByteArray?

    var targetsVersion: Long?

    /** The latest time seen, in seconds, so turning the device clock back does not revive an expired list. */
    var clockFloor: Long
}

/**
 * Refuses a JSON file from the config repository unless signed/targets.json, signed under a trusted root, gives its
 * sha256. A refused file comes back as a 502, so callers treat it like an unreachable source and keep what they have
 * -- the stored chains, the bundled lists -- instead of reading a file someone else wrote.
 *
 * Added before [ConfigMirrorInterceptor], so it checks whatever the mirror chain returned, from whichever host.
 * Roots after the first are published as signed/root.N.json and followed one version at a time.
 */
class SignedConfigInterceptor(
    bundledRoot: ByteArray,
    private val store: SignedConfigStore,
    private val check: SignatureCheck = SubstrateSignatureCheck,
    private val refreshAfterSeconds: Long = TimeUnit.MINUTES.toSeconds(5),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : Interceptor {

    companion object {
        const val OWNER = "pezkuwichain"
        const val REPO = "pezkuwi-wallet-utils"
        const val SIGNED_DIR = "signed/"
        const val TARGETS_PATH = "signed/targets.json"

        const val REFUSED_CODE = 502

        /** Roots a single refresh follows at most; a longer chain is finished on the next refresh. */
        private const val MAX_ROOTS_PER_REFRESH = 16
    }

    private val bundled = SignedConfigRules.trustBundledRoot(Envelope.parse(bundledRoot))

    private var targets: TargetsMetadata? = null
    private var targetsFetchedAt = Long.MIN_VALUE

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val file = GithubFile.parse(request.url)

        if (request.method != "GET" || file == null || !isSigned(file)) return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val contentType = response.body?.contentType()
        val content = response.body?.use { it.bytes() } ?: ByteArray(0)

        val trusted = try {
            matchingTargets(chain, file, content)
        } catch (e: IOException) {
            return refused(response, "signed list unavailable: ${e.message}")
        } catch (e: SignedConfigException) {
            return refused(response, e.message.orEmpty())
        }

        if (trusted == null) return refused(response, "${file.path} is not in the signed list")

        return response.newBuilder().body(content.toResponseBody(contentType)).build()
    }

    private fun isSigned(file: GithubFile): Boolean {
        return file.owner == OWNER && file.repo == REPO && file.path.endsWith(".json") && !file.path.startsWith(SIGNED_DIR)
    }

    /** The trusted targets that give [content] for [file], refreshing once if the list in hand does not. */
    @Synchronized
    private fun matchingTargets(chain: Interceptor.Chain, file: GithubFile, content: ByteArray): TargetsMetadata? {
        val now = now()
        val current = targets
        if (current != null && now - targetsFetchedAt < refreshAfterSeconds && current.matches(file.path, content)) return current

        // A file newer than the list in hand, or a list gone stale: fetch past any cached copy. If the list cannot be
        // fetched, one already verified and not yet expired still vouches for the files it names.
        val fresh = try {
            refresh(chain, file, now)
        } catch (e: IOException) {
            if (current != null && current.expiresAt > now && current.matches(file.path, content)) return current
            throw e
        }
        return fresh.takeIf { it.matches(file.path, content) }
    }

    private fun refresh(chain: Interceptor.Chain, file: GithubFile, now: Long): TargetsMetadata {
        // A stored root was verified against its predecessor when it was stored; one that no longer parses is dropped
        // rather than left to refuse every file from now on.
        var root = store.trustedRoot?.let { stored ->
            runCatching { RootMetadata.parse(Envelope.parse(stored)) }.getOrElse {
                store.trustedRoot = null
                null
            }
        } ?: bundled

        for (i in 0 until MAX_ROOTS_PER_REFRESH) {
            val next = fetch(chain, file, "${SIGNED_DIR}root.${root.version + 1}.json") ?: break
            root = SignedConfigRules.nextRoot(root, Envelope.parse(next), check)
            store.trustedRoot = next
        }

        val envelope = fetch(chain, file, TARGETS_PATH) ?: throw SignedConfigException("$TARGETS_PATH is missing")
        val verified = SignedConfigRules.verifyTargets(root, Envelope.parse(envelope), store.targetsVersion, now, check)

        store.targetsVersion = verified.version
        store.clockFloor = maxOf(store.clockFloor, now)
        targets = verified
        targetsFetchedAt = now
        return verified
    }

    /** A metadata file from the same repository and ref as [like]; null when it does not exist. */
    private fun fetch(chain: Interceptor.Chain, like: GithubFile, path: String): ByteArray? {
        val url = "https://${ConfigMirrorInterceptor.GITHUB_RAW_HOST}/${like.owner}/${like.repo}/${like.ref}/$path".toHttpUrl()
        val request = Request.Builder().url(url).cacheControl(CacheControl.Builder().noCache().build()).build()

        chain.proceed(request).use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("$path: HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("$path: empty body")
        }
    }

    private fun now(): Long = maxOf(clock(), store.clockFloor)

    private fun refused(original: Response, reason: String): Response {
        return original.newBuilder()
            .code(REFUSED_CODE)
            .message("Refused unsigned config: $reason")
            .protocol(Protocol.HTTP_1_1)
            .body(reason.toResponseBody("text/plain".toMediaType()))
            .build()
    }
}
