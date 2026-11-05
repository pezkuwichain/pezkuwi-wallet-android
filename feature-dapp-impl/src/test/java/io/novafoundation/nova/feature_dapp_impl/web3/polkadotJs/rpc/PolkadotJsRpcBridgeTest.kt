package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc

import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolkadotJsRpcBridgeTest {

    private val calls = mutableListOf<Triple<ChainId, String, List<Any>>>()
    private val unsubscribes = mutableListOf<String>()

    private val rpc = object : ChainRpc {
        override suspend fun send(chainId: ChainId, method: String, params: List<Any>): Any? {
            calls += Triple(chainId, method, params)
            return "ok"
        }

        override fun subscribe(chainId: ChainId, method: String, params: List<Any>, unsubscribeMethod: String): Flow<Any?> {
            calls += Triple(chainId, method, params)
            unsubscribes += unsubscribeMethod
            return flowOf("head")
        }
    }

    private val bridge = PolkadotJsRpcBridge(rpc)

    @Test
    fun `calls go to the chain of the started provider`() = runBlocking {
        val meta = bridge.start("zagros-people")

        assertEquals("Zagros People", meta.network)
        assertEquals("ok", bridge.send("state_getStorage", listOf("0x00")))
        assertEquals("c72b38f36affaeb2661018b201c2be02e8c09fc699640525d39200a10fd77878", calls.single().first)
    }

    @Test
    fun `nothing is sent before a provider is started or for an unknown one`() = runBlocking {
        assertTrue(runCatching { bridge.send("chain_getHeader", emptyList()) }.exceptionOrNull() is RpcBridgeException)
        assertTrue(runCatching { bridge.start("polkadot") }.exceptionOrNull() is RpcBridgeException)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `methods outside the list never reach the node`() = runBlocking {
        bridge.start("pezkuwi")

        listOf("author_rotateKeys", "author_insertKey", "system_addReservedPeer", "state_getPairs", "offchain_localStorageSet", "eth_sendTransaction")
            .forEach { method -> assertTrue(method, runCatching { bridge.send(method, emptyList()) }.exceptionOrNull() is RpcBridgeException) }
        assertTrue(runCatching { bridge.subscribe("state_subscribeStorage_", emptyList()) }.exceptionOrNull() is RpcBridgeException)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a subscription carries the call that ends it`() = runBlocking {
        bridge.start("pezkuwi-asset-hub")

        assertEquals(listOf("head"), bridge.subscribe("chain_subscribeNewHeads", emptyList()).toList())
        assertEquals(listOf("chain_unsubscribeNewHeads"), unsubscribes)
    }

    @Test
    fun `every provider is listed with the wallet as its source`() {
        val providers = bridge.providers()

        assertEquals(setOf("pezkuwi", "pezkuwi-asset-hub", "pezkuwi-people", "zagros", "zagros-asset-hub", "zagros-people"), providers.keys)
        assertTrue(providers.values.all { it.source == "pezkuwi-wallet" })
    }

    @Test
    fun `a subscription ends when its signal fires`() = runBlocking {
        val upstream = MutableSharedFlow<Int>()
        val left = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()

        val job = launch { upstream.until { left.await() }.collect { received += it } }
        upstream.subscriptionCount.first { it > 0 }
        upstream.emit(1)
        while (received.isEmpty()) yield()
        left.complete(Unit)
        job.join()

        assertEquals(listOf(1), received)
        assertEquals(0, upstream.subscriptionCount.value)
    }

    @Test
    fun `only kurd pages and same origins are recognised`() {
        assertTrue(isKurdPage("https://dks.kurd/news/1"))
        assertFalse(isKurdPage("https://dks.kurd.example.com/"))
        assertFalse(isKurdPage("https://app.pezkuwichain.io/"))
        assertTrue(sameOrigin("https://dks.kurd/a", "https://dks.kurd/b?c"))
        assertFalse(sameOrigin("https://dks.kurd/", "https://serok.gov.kurd/"))
    }
}
