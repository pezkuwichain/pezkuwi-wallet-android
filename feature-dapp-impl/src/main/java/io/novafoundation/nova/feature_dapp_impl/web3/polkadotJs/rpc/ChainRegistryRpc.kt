package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc

import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.multiNetwork.getSocket
import io.novasama.substrate_sdk_android.wsrpc.executeAsync
import io.novasama.substrate_sdk_android.wsrpc.request.runtime.RuntimeRequest
import io.novasama.substrate_sdk_android.wsrpc.subscriptionFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Over each chain's own connection, which moves to the light client when its RPC nodes fail. */
class ChainRegistryRpc(private val chainRegistry: ChainRegistry) : ChainRpc {

    override suspend fun send(chainId: ChainId, method: String, params: List<Any>): Any? {
        val response = chainRegistry.getSocket(chainId).executeAsync(RuntimeRequest(method, params))
        response.error?.let { throw RpcBridgeException(it.message) }
        return response.result
    }

    override fun subscribe(chainId: ChainId, method: String, params: List<Any>, unsubscribeMethod: String): Flow<Any?> = channelFlow {
        val socket = chainRegistry.getSocket(chainId)
        launch { socket.subscriptionFlow(RuntimeRequest(method, params), unsubscribeMethod).map { it.params.result }.collect { send(it) } }
        awaitClose()
    }
}
