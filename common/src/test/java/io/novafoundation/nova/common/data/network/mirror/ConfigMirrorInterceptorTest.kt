package io.novafoundation.nova.common.data.network.mirror

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

private const val RAW = "https://raw.githubusercontent.com/pezkuwichain/pezkuwi-wallet-utils/master/chains/v22/android/chains.json"
private const val CDN = "https://cdn.jsdelivr.net/gh/pezkuwichain/pezkuwi-wallet-utils@master/chains/v22/android/chains.json"
private const val FASTLY = "https://fastly.jsdelivr.net/gh/pezkuwichain/pezkuwi-wallet-utils@master/chains/v22/android/chains.json"

class ConfigMirrorInterceptorTest {

    private var now = 0L
    private val hits = mutableListOf<String>()

    /** What each host does, standing in for the network: a status code, or null for a connection failure. */
    private val hosts = mutableMapOf<String, Int?>()

    private val client = OkHttpClient.Builder()
        .addInterceptor(ConfigMirrorInterceptor(hostDownMillis = 1_000, clock = { now }))
        .addInterceptor(Interceptor { chain -> fakeNetwork(chain.request()) })
        .build()

    @Test
    fun `a blocked primary is served from the first mirror`() {
        hosts["raw.githubusercontent.com"] = null
        hosts["cdn.jsdelivr.net"] = 200

        assertEquals("cdn.jsdelivr.net", fetch(RAW))
        assertEquals(listOf(RAW, CDN), hits)
    }

    @Test
    fun `mirrors are tried in order until one answers`() {
        hosts["raw.githubusercontent.com"] = 451
        hosts["cdn.jsdelivr.net"] = null
        hosts["fastly.jsdelivr.net"] = 200

        assertEquals("fastly.jsdelivr.net", fetch(RAW))
        assertEquals(listOf(RAW, CDN, FASTLY), hits)
    }

    @Test
    fun `a server error on the primary goes to a mirror`() {
        hosts["raw.githubusercontent.com"] = 503
        hosts["cdn.jsdelivr.net"] = 200

        assertEquals("cdn.jsdelivr.net", fetch(RAW))
    }

    @Test
    fun `a missing file is an answer and no mirror is asked`() {
        hosts["raw.githubusercontent.com"] = 404

        assertNull(fetch(RAW))
        assertEquals(listOf(RAW), hits)
    }

    @Test
    fun `a failed primary is skipped until its window passes`() {
        hosts["raw.githubusercontent.com"] = null
        hosts["cdn.jsdelivr.net"] = 200
        fetch(RAW)
        hits.clear()

        fetch(RAW)
        assertEquals("the primary is not waited on again inside the window", listOf(CDN), hits)

        now += 1_001
        hits.clear()
        hosts["raw.githubusercontent.com"] = 200
        assertEquals("raw.githubusercontent.com", fetch(RAW))
        assertEquals(listOf(RAW), hits)
    }

    @Test(expected = IOException::class)
    fun `when every source fails the failure reaches the caller`() {
        fetch(RAW)
    }

    @Test
    fun `other hosts are left alone`() {
        hosts["example.org"] = null

        runCatching { fetch("https://example.org/a.json") }
        assertEquals(listOf("https://example.org/a.json"), hits)
    }

    @Test
    fun `the long ref form maps to the mirror's short form`() {
        val file = GithubFile.parse("https://raw.githubusercontent.com/o/r/refs/heads/master/a/b.json".toHttpUrl())

        assertEquals(GithubFile("o", "r", "master", "a/b.json"), file)
    }

    private fun fetch(url: String): String? {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            return if (response.isSuccessful) response.body!!.string() else null
        }
    }

    private fun fakeNetwork(request: Request): Response {
        hits += request.url.toString()

        val code = hosts[request.url.host] ?: throw IOException("unreachable: ${request.url.host}")

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(request.url.host.toResponseBody("text/plain".toMediaType()))
            .build()
    }
}
