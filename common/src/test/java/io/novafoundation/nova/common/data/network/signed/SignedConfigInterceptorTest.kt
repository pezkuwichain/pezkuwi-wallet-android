package io.novafoundation.nova.common.data.network.signed

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

private const val BASE = "https://raw.githubusercontent.com/pezkuwichain/pezkuwi-wallet-utils/master/"
private const val CHAINS = "chains/v22/android/chains.json"

class SignedConfigInterceptorTest {

    /** sr25519 is checked with Ed25519 math here, as in [SignedConfigTest]: the native library does not load on a JVM. */
    private val check = SignatureCheck { _, publicKey, message, signature -> verifyEd25519(publicKey, message, signature) }

    private class Key(val scheme: String) {
        val private = Ed25519PrivateKeyParameters(SecureRandom())
        val public: ByteArray = private.generatePublicKey().encoded
        val id = keyIdOf(public)

        fun sign(payload: ByteArray): ByteArray {
            val message = if (scheme == "sr25519") "<Bytes>".toByteArray() + payload + "</Bytes>".toByteArray() else payload
            return Ed25519Signer().apply {
                init(true, private)
                update(message, 0, message.size)
            }.generateSignature()
        }
    }

    private class MemoryStore : SignedConfigStore {
        override var trustedRoot: ByteArray? = null
        override var targetsVersion: Long? = null
        override var clockFloor: Long = 0
    }

    private var now = 1_800_000_000L
    private val rootKeys = List(3) { Key("sr25519") }
    private val targetsKey = Key("ed25519")
    private val store = MemoryStore()

    /** The repository as served: path -> content; a missing path is a 404, a path in [unreachable] a failure. */
    private val served = mutableMapOf<String, ByteArray>()
    private val unreachable = mutableSetOf<String>()
    private val hits = mutableListOf<String>()

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(SignedConfigInterceptor(envelope(rootPayload(1, rootKeys, targetsKey)), store, check, refreshAfterSeconds = 300, clock = { now }))
            .addInterceptor(Interceptor { chain -> serve(chain.request()) })
            .build()
    }

    private val chains = "[\"pezkuwi\"]".toByteArray()

    @Test
    fun `a file the signed list gives is returned unchanged`() {
        publish(CHAINS to chains)

        assertEquals("[\"pezkuwi\"]", fetch(CHAINS))
    }

    @Test
    fun `a file changed after signing is refused`() {
        publish(CHAINS to chains)
        served[CHAINS] = "[\"elsewhere\"]".toByteArray()

        assertNull(fetch(CHAINS))
    }

    @Test
    fun `a file the list does not name is refused`() {
        publish(CHAINS to chains)
        served["dapps/dapps_full.json"] = "[]".toByteArray()

        assertNull(fetch("dapps/dapps_full.json"))
    }

    @Test
    fun `a list signed by a stranger is refused`() {
        publish(CHAINS to chains, signer = Key("ed25519"))

        assertNull(fetch(CHAINS))
    }

    @Test
    fun `without a signed list nothing is accepted`() {
        served[CHAINS] = chains

        assertNull(fetch(CHAINS))
    }

    @Test
    fun `images, other repositories and missing files pass untouched`() {
        served["icons/tokens/white/HEZ.png"] = "png".toByteArray()

        assertEquals("png", fetch("icons/tokens/white/HEZ.png"))
        assertEquals(404, code(BASE + "chains/v22/android/nothing.json"))
        assertEquals(200, code("https://raw.githubusercontent.com/polkadot-js/phishing/master/all.json"))
        assertEquals(listOf("icons/tokens/white/HEZ.png", "chains/v22/android/nothing.json", "polkadot-js"), hits)
    }

    @Test
    fun `a file newer than the list in hand fetches the list again`() {
        publish(CHAINS to chains)
        fetch(CHAINS)

        val newer = "[\"pezkuwi\",\"zagros\"]".toByteArray()
        publish(CHAINS to newer, version = 2)

        assertEquals("[\"pezkuwi\",\"zagros\"]", fetch(CHAINS))
        assertEquals(2L, store.targetsVersion)
    }

    @Test
    fun `a verified list still vouches for its files while the list cannot be fetched`() {
        publish(CHAINS to chains)
        fetch(CHAINS)

        now += 600
        unreachable += TARGETS_PATH

        assertEquals("[\"pezkuwi\"]", fetch(CHAINS))
    }

    @Test
    fun `with no verified list in hand, an unreachable list means refusal`() {
        publish(CHAINS to chains)
        unreachable += TARGETS_PATH

        assertNull(fetch(CHAINS))
    }

    @Test
    fun `an older list is refused once a newer one was trusted, even after a restart`() {
        store.targetsVersion = 7
        publish(CHAINS to chains, version = 6)

        assertNull(fetch(CHAINS))
    }

    @Test
    fun `turning the clock back does not revive an expired list`() {
        store.clockFloor = now + 2_000
        publish(CHAINS to chains, expires = now + 1_000)

        assertNull(fetch(CHAINS))
    }

    @Test
    fun `a rotated root replaces the targets key and is remembered`() {
        val newTargetsKey = Key("ed25519")
        val root2 = rootPayload(2, rootKeys, newTargetsKey)
        served["signed/root.2.json"] = envelope(root2, rootKeys[0], rootKeys[2])
        publish(CHAINS to chains, signer = newTargetsKey)

        assertEquals("[\"pezkuwi\"]", fetch(CHAINS))
        assertNotNull(store.trustedRoot)

        // The old targets key no longer signs anything.
        publish(CHAINS to chains, version = 2, signer = targetsKey)
        now += 600
        assertNull(fetch(CHAINS))
    }

    @Test
    fun `a root signed by one root key is not followed`() {
        val newTargetsKey = Key("ed25519")
        served["signed/root.2.json"] = envelope(rootPayload(2, rootKeys, newTargetsKey), rootKeys[0])
        publish(CHAINS to chains, signer = newTargetsKey)

        assertNull(fetch(CHAINS))
        assertNull(store.trustedRoot)
    }

    private fun publish(vararg files: Pair<String, ByteArray>, version: Long = 1, expires: Long = now + 10_000, signer: Key = targetsKey) {
        files.forEach { (path, content) -> served[path] = content }
        val entries = files.joinToString(",") { (path, content) -> "\"$path\":\"${sha256Hex(content)}\"" }
        served[TARGETS_PATH] = envelope("""{"type":"targets","version":$version,"expires":$expires,"files":{$entries}}""", signer)
    }

    private fun rootPayload(version: Long, root: List<Key>, targets: Key): String {
        val keys = (root + targets).joinToString(",") { """"${it.id}":{"scheme":"${it.scheme}","public":"${Base64.toBase64String(it.public)}"}""" }
        val ids = root.joinToString(",") { "\"${it.id}\"" }
        return """{"type":"root","version":$version,"expires":${now + 100_000},"keys":{$keys},""" +
            """"root":{"keyids":[$ids],"threshold":2},"targets":{"keyids":["${targets.id}"],"threshold":1}}"""
    }

    private fun envelope(payload: String, vararg signers: Key): ByteArray {
        val bytes = payload.toByteArray()
        val signatures = signers.joinToString(",") { """{"keyid":"${it.id}","sig":"${Base64.toBase64String(it.sign(bytes))}"}""" }
        return """{"signed":"${Base64.toBase64String(bytes)}","signatures":[$signatures]}""".toByteArray()
    }

    private fun fetch(path: String): String? {
        client.newCall(Request.Builder().url(BASE + path).build()).execute().use { response ->
            return if (response.isSuccessful) response.body!!.string() else null
        }
    }

    private fun code(url: String): Int = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    private fun serve(request: Request): Response {
        val url = request.url.toString()
        val path = url.removePrefix(BASE)
        if (!path.startsWith("signed/")) hits += if (url.startsWith(BASE)) path else url.split("/")[3]
        if (path in unreachable) throw IOException("unreachable: $path")

        val content = if (url.startsWith(BASE)) served[path] else "{}".toByteArray()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (content == null) 404 else 200)
            .message("")
            .body((content ?: ByteArray(0)).toResponseBody("application/json".toMediaType()))
            .build()
    }

    private companion object {
        const val TARGETS_PATH = SignedConfigInterceptor.TARGETS_PATH
    }
}
