package io.novafoundation.nova.runtime.names.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KurdContentFetcherTest {

    private val root = cid("bafybeietjm63oynimmv5yyqay33nui4y4wx6u3peezwetxgiwvfmelutzu")
    private val car = resource("subdir-with-two-single-block-files.car")
    private val tampered = car.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }

    @Test
    fun `a gateway that changed a block is skipped for the next one`() = runBlocking {
        val asked = mutableListOf<String>()
        val source = CarSource { gateway, _, _ ->
            asked += gateway
            if (gateway == "https://bad") tampered else car
        }

        val result = KurdContentFetcher(gateways("https://bad", "https://good"), source).fetch(root, listOf("subdir", "hello.txt"))

        assertEquals("hello world\n", String((result as KurdContentFetcher.Result.File).bytes))
        assertEquals(listOf("https://bad", "https://good"), asked)
    }

    @Test
    fun `not found is believed only when the parent directory proves it`() = runBlocking {
        // Withholds the file although its directory lists it: not an answer, so the next gateway is asked
        val withholding = CarSource { gateway, _, path -> if (gateway == "https://withholding" && path.size == 2) null else car }

        val result = KurdContentFetcher(gateways("https://withholding", "https://good"), withholding).fetch(root, listOf("subdir", "hello.txt"))
        assertTrue(result is KurdContentFetcher.Result.File)

        val honest = CarSource { _, _, path -> if (path.size == 2) null else car }
        assertSame(KurdContentFetcher.Result.NotFound, KurdContentFetcher(gateways("https://one"), honest).fetch(root, listOf("subdir", "missing.txt")))
    }

    @Test
    fun `a gateway proving the name absent in one answer is believed`() = runBlocking {
        // Kubo's trustless gateway answers a missing path with the directory blocks that lack it
        val asked = mutableListOf<List<String>>()
        val source = CarSource { _, _, path -> asked += path; car }

        assertSame(KurdContentFetcher.Result.NotFound, KurdContentFetcher(gateways("https://one"), source).fetch(root, listOf("subdir", "missing.txt")))
        assertEquals(listOf(listOf("subdir", "missing.txt")), asked)
    }

    @Test
    fun `a directory is served through its index and without one is not found`() = runBlocking {
        val source = CarSource { _, _, path -> if (path.lastOrNull() == KurdContentFetcher.INDEX) null else car }

        assertSame(KurdContentFetcher.Result.NotFound, KurdContentFetcher(gateways("https://one"), source).fetch(root, listOf("subdir")))
    }

    @Test
    fun `no verifiable answer anywhere is unavailable`() = runBlocking {
        val result = KurdContentFetcher(gateways("https://a", "https://b"), CarSource { _, _, _ -> tampered }).fetch(root, listOf("subdir", "hello.txt"))

        assertSame(KurdContentFetcher.Result.Unavailable, result)
    }

    @Test
    fun `gateway entries are origins only`() {
        assertEquals("https://ipfs.io", RealKurdGateways.normalized("https://IPFS.io/"))
        assertEquals("https://mirror.example:8443", RealKurdGateways.normalized("https://mirror.example:8443"))
        listOf("http://ipfs.io", "https://ipfs.io/ipfs/", "https://ipfs.io?x=1", "https://user@ipfs.io", "ipfs.io").forEach {
            assertEquals(it, null, RealKurdGateways.normalized(it))
        }
    }

    @Test
    fun `the built-in list stands when the signed one cannot be read`() = runBlocking {
        val failing = object : KurdGatewayListApi {
            override suspend fun gateways(url: String): GatewayListRemote = throw java.io.IOException("offline")
        }
        val signed = object : KurdGatewayListApi {
            override suspend fun gateways(url: String) = GatewayListRemote(
                listOf(GatewayListRemote.GatewayRemote("https://mirror.pezkuwi.example"), GatewayListRemote.GatewayRemote("http://plain"))
            )
        }

        assertEquals(RealKurdGateways.BUILT_IN, RealKurdGateways(failing).list())
        assertEquals(listOf("https://mirror.pezkuwi.example"), RealKurdGateways(signed).list())
    }
}
