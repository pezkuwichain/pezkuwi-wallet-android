package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc

import io.novafoundation.nova.feature_dapp_impl.web3.webview.WebViewWeb3JavaScriptInterface
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.multiNetwork.connection.light.LightClients
import io.novafoundation.nova.runtime.names.KurdName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.net.URI

/** polkadot-js `ProviderMeta`, what `pub(rpc.listProviders)` and `pub(rpc.startProvider)` describe. */
class ProviderMeta(val network: String, val node: String, val source: String, val transport: String)

class RpcBridgeException(message: String) : Exception(message)

/** The chain connections the bridge forwards to. */
interface ChainRpc {

    suspend fun send(chainId: ChainId, method: String, params: List<Any>): Any?

    fun subscribe(chainId: ChainId, method: String, params: List<Any>, unsubscribeMethod: String): Flow<Any?>
}

/**
 * The provider half of the polkadot-js extension API (`pub(rpc.*)`), for one tab. A `.kurd` page
 * may not open connections of its own (the wallet's policy allows only its own name), so this is
 * how it reaches the chain: over the wallet's connection, which falls back to the light client.
 * Only methods that read state or submit an already signed extrinsic pass.
 */
class PolkadotJsRpcBridge(private val rpc: ChainRpc) {

    private var started: ChainId? = null

    fun providers(): Map<String, ProviderMeta> = PROVIDERS.mapValues { (_, provider) -> provider.meta }

    fun start(key: String): ProviderMeta {
        val provider = PROVIDERS[key] ?: throw RpcBridgeException("Unknown provider $key")
        started = provider.chainId
        return provider.meta
    }

    suspend fun send(method: String, params: List<Any>): Any? {
        if (method !in CALLS) throw RpcBridgeException("Method $method is not available")
        return rpc.send(startedChain(), method, params)
    }

    fun subscribe(method: String, params: List<Any>): Flow<Any?> {
        val unsubscribe = SUBSCRIPTIONS[method] ?: throw RpcBridgeException("Subscription $method is not available")
        return rpc.subscribe(startedChain(), method, params, unsubscribe)
    }

    private fun startedChain(): ChainId = started ?: throw RpcBridgeException("No provider started")

    private class Provider(val chainId: ChainId, val meta: ProviderMeta)

    companion object {

        private const val SOURCE = "pezkuwi-wallet"

        private const val ZAGROS_ASSET_HUB: ChainId = "063f739077881df9f291548de301b2e11d6a45cc545c24e7bf18d8a40f0ce182"

        private fun provider(chainId: ChainId, network: String): Provider {
            return Provider(chainId, ProviderMeta(network, node = "full", source = SOURCE, transport = "WsProvider"))
        }

        private val PROVIDERS = mapOf(
            "pezkuwi" to provider(ChainGeneses.PEZKUWI, "Pezkuwi"),
            "pezkuwi-asset-hub" to provider(ChainGeneses.PEZKUWI_ASSET_HUB, "Pezkuwi Asset Hub"),
            "pezkuwi-people" to provider(ChainGeneses.PEZKUWI_PEOPLE, "Pezkuwi People"),
            "zagros" to provider(LightClients.ZAGROS, "Zagros"),
            "zagros-asset-hub" to provider(ZAGROS_ASSET_HUB, "Zagros Asset Hub"),
            "zagros-people" to provider(LightClients.ZAGROS_PEOPLE, "Zagros People"),
        )

        /** Reads, fee estimates and submitting an extrinsic the user already signed in the wallet. */
        internal val CALLS = setOf(
            "chain_getBlock", "chain_getBlockHash", "chain_getFinalizedHead", "chain_getHeader",
            "state_call", "state_getKeysPaged", "state_getMetadata", "state_getRuntimeVersion", "state_getStorage",
            "state_getStorageHash", "state_getStorageSize", "state_queryStorageAt", "state_getReadProof",
            "system_accountNextIndex", "system_chain", "system_chainType", "system_health", "system_name",
            "system_properties", "system_version", "rpc_methods",
            "payment_queryInfo", "payment_queryFeeDetails",
            "author_submitExtrinsic",
        )

        /** Subscriptions, each with the call that ends it. */
        internal val SUBSCRIPTIONS = mapOf(
            "chain_subscribeNewHeads" to "chain_unsubscribeNewHeads",
            "chain_subscribeFinalizedHeads" to "chain_unsubscribeFinalizedHeads",
            "chain_subscribeAllHeads" to "chain_unsubscribeAllHeads",
            "state_subscribeStorage" to "state_unsubscribeStorage",
            "state_subscribeRuntimeVersion" to "state_unsubscribeRuntimeVersion",
            "author_submitAndWatchExtrinsic" to "author_unwatchExtrinsic",
        )
    }
}

/** True when [pageUrl] is on a `.kurd` host. */
fun isKurdPage(pageUrl: String): Boolean = KurdName.isKurdHost(runCatching { URI(pageUrl).host }.getOrNull())

fun sameOrigin(first: String, second: String): Boolean {
    val one = WebViewWeb3JavaScriptInterface.originOf(first) ?: return false
    return one == WebViewWeb3JavaScriptInterface.originOf(second)
}

/** This flow until [signal] returns, then it completes and its upstream is cancelled. */
fun <T> Flow<T>.until(signal: suspend () -> Unit): Flow<T> = channelFlow {
    val collector = launch { collect { send(it) } }
    val watcher = launch {
        signal()
        collector.cancel()
    }
    collector.join()
    watcher.cancel()
}
