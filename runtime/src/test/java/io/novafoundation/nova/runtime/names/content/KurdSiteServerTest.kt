package io.novafoundation.nova.runtime.names.content

import io.novafoundation.nova.runtime.names.KurdName
import io.novafoundation.nova.runtime.names.KurdNameResolver
import io.novafoundation.nova.runtime.names.KurdResolution
import io.novafoundation.nova.runtime.names.NameRecord
import io.novafoundation.nova.runtime.names.NameStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KurdSiteServerTest {

    private val siteRoot = cid("bafybeietjm63oynimmv5yyqay33nui4y4wx6u3peezwetxgiwvfmelutzu")
    private val siteCar = resource("subdir-with-two-single-block-files.car")

    @Test
    fun `a file of a resolved name is served with the wallet's policy`() = runBlocking {
        val response = server(resolved(ipfs(siteRoot))).serve("dks.kurd", "/subdir/hello.txt")

        assertEquals(200, response.status)
        assertEquals("text/plain", response.mimeType)
        assertEquals("hello world\n", String(response.body))
        assertEquals(KurdSiteServer.CONTENT_SECURITY_POLICY, response.headers["Content-Security-Policy"])
    }

    @Test
    fun `each resolution outcome has its status`() = runBlocking {
        val name = KurdName.parse("dks.kurd")!!

        assertEquals(404, server { KurdResolution.NotFound(it, verified = true) }.serve("dks.kurd", "/").status)
        assertEquals(403, server { KurdResolution.Refused(it) }.serve("dks.kurd", "/").status)
        assertEquals(502, server { KurdResolution.Unavailable(it) }.serve("dks.kurd", "/").status)
        assertEquals(404, server(resolved(content = null)).serve(name.name, "/").status)
    }

    @Test
    fun `content the server cannot verify is not served`() = runBlocking {
        val ipns = NameRecord.Content.Ipfs(byteArrayOf(0xe5.toByte(), 0x01) + siteRoot.bytes)
        val feed = NameRecord.Content.Feed(ByteArray(32), ByteArray(32), siteRoot.bytes, java.math.BigInteger.ONE)

        assertEquals(415, server(resolved(ipns)).serve("dks.kurd", "/").status)
        assertEquals(415, server(resolved(feed)).serve("dks.kurd", "/").status)
    }

    @Test
    fun `an invalid name or path is refused before any lookup`() = runBlocking {
        val server = server { error("must not resolve") }

        assertEquals(404, server.serve("uni-x--edu.kurd", "/").status)
        assertEquals(400, server.serve("dks.kurd", "/%2e%2e/secret").status)
        assertEquals(400, server.serve("dks.kurd", "/a%2Fb").status)
        assertEquals(400, server.serve("dks.kurd", "/%zz").status)
    }

    @Test
    fun `a name is resolved once while its resolution is fresh`() = runBlocking {
        var calls = 0
        var now = 0L
        val server = KurdSiteServer(
            resolver = resolverOf { calls++; resolved(ipfs(siteRoot))(it) },
            fetcher = fetcher(CarSource { _, _, _ -> siteCar }),
            resolutionTtlMillis = 1_000,
            clock = { now },
        )

        server.serve("dks.kurd", "/subdir/hello.txt")
        server.serve("dks.kurd", "/subdir/ascii.txt")
        assertEquals(1, calls)

        now = 1_000
        server.serve("dks.kurd", "/subdir/hello.txt")
        assertEquals(2, calls)
    }

    @Test
    fun `paths decode per segment and only as UTF-8`() {
        assertEquals(listOf("a b", "ç.html"), KurdSiteServer.decodePath("/a%20b/%C3%A7.html?x=1#top"))
        assertEquals(emptyList<String>(), KurdSiteServer.decodePath("/"))
        assertNull(KurdSiteServer.decodePath("/%C3"))
        assertNull(KurdSiteServer.decodePath("/%+f"))
        assertNull(KurdSiteServer.decodePath("/%٣٣"))
    }

    @Test
    fun `types follow the extension`() {
        assertEquals("text/html", KurdSiteServer.mimeTypeOf("index.HTML"))
        assertEquals("text/javascript", KurdSiteServer.mimeTypeOf("app.mjs"))
        assertEquals("application/octet-stream", KurdSiteServer.mimeTypeOf("README"))
        assertEquals("application/octet-stream", KurdSiteServer.mimeTypeOf(null))
    }

    private fun server(resolve: (KurdName) -> KurdResolution) = KurdSiteServer(resolverOf(resolve), fetcher(CarSource { _, _, _ -> siteCar }))

    private fun resolverOf(resolve: (KurdName) -> KurdResolution) = object : KurdNameResolver {
        override suspend fun resolve(name: KurdName) = resolve(name)
    }

    private fun fetcher(source: CarSource) = KurdContentFetcher(gateways("https://one"), source)

    private fun ipfs(cid: Cid) = NameRecord.Content.Ipfs(byteArrayOf(0xe3.toByte(), 0x01) + cid.bytes)

    private fun resolved(content: NameRecord.Content?): (KurdName) -> KurdResolution = { name ->
        val record = NameRecord(
            label = name.labels.first(), parent = KurdName.KURD_NODE, parentReg = 0, reg = 1, owner = NameRecord.Owner.Governance,
            kind = NameRecord.Kind.GRANTED, lockedTransfer = true, expiry = null, account = null, content = content,
        )
        KurdResolution.Resolved(name, record, NameStatus.ACTIVE, verified = true)
    }
}

internal fun gateways(vararg urls: String) = object : KurdGateways {
    override suspend fun list() = urls.toList()
}
