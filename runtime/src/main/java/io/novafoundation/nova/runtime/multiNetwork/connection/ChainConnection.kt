package io.novafoundation.nova.runtime.multiNetwork.connection

import android.util.Log
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.common.utils.share
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.connection.autobalance.NodeAutobalancer
import io.novafoundation.nova.runtime.multiNetwork.connection.light.LightClients
import io.novafoundation.nova.runtime.multiNetwork.connection.light.LightFallbackPolicy
import io.novasama.substrate_sdk_android.wsrpc.SocketService
import io.novasama.substrate_sdk_android.wsrpc.interceptor.WebSocketResponseInterceptor
import io.novasama.substrate_sdk_android.wsrpc.interceptor.WebSocketResponseInterceptor.ResponseDelivery
import io.novasama.substrate_sdk_android.wsrpc.networkStateFlow
import io.novasama.substrate_sdk_android.wsrpc.response.RpcResponse
import io.novasama.substrate_sdk_android.wsrpc.state.SocketStateMachine.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import javax.inject.Provider

class ChainConnectionFactory(
    private val externalRequirementFlow: Flow<ChainConnection.ExternalRequirement>,
    private val nodeAutobalancer: NodeAutobalancer,
    private val socketServiceProvider: Provider<SocketService>,
    private val lightClients: LightClients,
) {

    suspend fun create(chain: Chain): ChainConnection {
        val connection = ChainConnection(
            socketService = socketServiceProvider.get(),
            externalRequirementFlow = externalRequirementFlow,
            nodeAutobalancer = nodeAutobalancer,
            initialChain = chain,
            lightClients = lightClients.takeIf { it.supports(chain.id) },
        )

        connection.setup()

        return connection
    }
}

private const val INFURA_ERROR_CODE = -32005
private const val ALCHEMY_ERROR_CODE = 429

private const val BLUST_CAPACITY_ERROR_CODE = -32098
private const val BLUST_RATE_LIMIT_ERROR_CODE = -32097

private const val ON_FINALITY_RATE_LIMIT_ERROR_CODE = -32029

/** How often a connection on the light client checks whether to retry RPC. */
private const val LIGHT_FALLBACK_TICK_MILLIS = 15_000L

private const val LIGHT_FALLBACK_TAG = "LightFallback"

private val RATE_LIMIT_ERROR_CODES = listOf(
    INFURA_ERROR_CODE,
    ALCHEMY_ERROR_CODE,
    BLUST_CAPACITY_ERROR_CODE,
    BLUST_RATE_LIMIT_ERROR_CODE,
    ON_FINALITY_RATE_LIMIT_ERROR_CODE
)

class ChainConnection internal constructor(
    val socketService: SocketService,
    private val externalRequirementFlow: Flow<ExternalRequirement>,
    private val nodeAutobalancer: NodeAutobalancer,
    initialChain: Chain,
    private val lightClients: LightClients? = null,
) : CoroutineScope by CoroutineScope(Dispatchers.Default),
    WebSocketResponseInterceptor {

    enum class ExternalRequirement {
        ALLOWED, STOPPED
    }

    val state = socketService.networkStateFlow()
        .stateIn(scope = this, started = SharingStarted.Eagerly, initialValue = State.Disconnected)

    private val responseRequiresNodeChangeFlow = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    private val nodeChangeSignal = merge(
        state.nodeChangeEvents(),
        responseRequiresNodeChangeFlow
    ).shareIn(scope = this, started = SharingStarted.Eagerly)

    private val chain = MutableStateFlow(initialChain)

    private val availableNodes = chain.map { it.nodes }
        .distinctUntilChanged()
        .share(SharingStarted.Eagerly)

    private val rpcUrl = nodeAutobalancer.connectionUrlFlow(
        chainId = initialChain.id,
        changeConnectionEventFlow = nodeChangeSignal,
        availableNodesFlow = availableNodes,
    )

    val currentUrl = (lightClients?.let { rpcUrl.withLightFallback(it) } ?: rpcUrl)
        .share(SharingStarted.Eagerly)

    suspend fun setup() {
        socketService.setInterceptor(this)

        observeCurrentNode()

        externalRequirementFlow.onEach {
            if (it == ExternalRequirement.ALLOWED) {
                socketService.resume()
            } else {
                socketService.pause()
            }
        }
            .launchIn(this)
    }

    private suspend fun observeCurrentNode() {
        // Important - this should be awaited first before setting up externalRequirementFlow subscription
        // Otherwise there might be a race between both of them
        val firstNodeUrl = currentUrl.first()?.saturatedUrl ?: return
        socketService.start(firstNodeUrl, remainPaused = true)

        currentUrl.mapNotNull { it?.saturatedUrl }
            .filter { nodeUrl -> actualUrl() != nodeUrl }
            .onEach { nodeUrl -> socketService.switchUrl(nodeUrl) }
            .onEach { nodeUrl -> Log.d(this@ChainConnection.LOG_TAG, "Switching node in ${chain.value.name} to $nodeUrl") }
            .launchIn(this)
    }

    fun updateChain(chain: Chain) {
        this.chain.value = chain
    }

    fun finish() {
        cancel()

        socketService.stop()
    }

    private suspend fun actualUrl(): String? = state.first().urlOrNull()

    private fun Flow<State>.nodeChangeEvents(): Flow<Unit> {
        return mapNotNull { stateValue ->
            Unit.takeIf { stateValue.needsAutobalance() }
        }
    }

    private fun State.needsAutobalance() = this is State.WaitingForReconnect && attempt > 1

    private sealed interface FallbackEvent {
        class Rpc(val node: NodeWithSaturatedUrl?) : FallbackEvent
        class Socket(val state: State) : FallbackEvent
        object Tick : FallbackEvent
    }

    /**
     * Moves the connection to the light client once every RPC node has failed in a row, and back
     * to RPC when a retry connects (see [LightFallbackPolicy]). Events are handled one at a time,
     * in the order they arrive.
     */
    private fun Flow<NodeWithSaturatedUrl?>.withLightFallback(lightClients: LightClients): Flow<NodeWithSaturatedUrl?> {
        Log.d(LIGHT_FALLBACK_TAG, "${chain.value.name}: light client fallback armed")
        val policy = LightFallbackPolicy()
        var latestRpc: NodeWithSaturatedUrl? = null
        var light: NodeWithSaturatedUrl? = null
        val ticks = flow {
            while (true) {
                delay(LIGHT_FALLBACK_TICK_MILLIS)
                emit(FallbackEvent.Tick)
            }
        }
        val rpcNodeCount = { chain.value.nodes.nodes.count { it.connectionType == Chain.Node.ConnectionType.WSS } }

        return merge(map(FallbackEvent::Rpc), state.map(FallbackEvent::Socket), ticks).transform { event ->
            when (event) {
                is FallbackEvent.Rpc -> {
                    latestRpc = event.node
                    if (policy.source == LightFallbackPolicy.Source.RPC) emit(event.node)
                }

                is FallbackEvent.Socket -> {
                    val onLight = light != null && event.state.urlOrNull() == light?.saturatedUrl
                    if (event.state is State.Connected && !onLight) policy.onRpcConnected(System.currentTimeMillis())

                    if (event.state.needsAutobalance()) {
                        Log.d(LIGHT_FALLBACK_TAG, "${chain.value.name}: ${event.state} failed, source ${policy.source}, on light $onLight")
                        if (onLight) {
                            policy.onLightUnavailable(rpcNodeCount())
                            emit(latestRpc)
                        } else if (policy.onRpcFailure(rpcNodeCount(), System.currentTimeMillis())) {
                            val url = lightClients.url(chain.value.id)
                            Log.d(LIGHT_FALLBACK_TAG, "${chain.value.name}: light client url $url")
                            if (url == null) {
                                policy.onLightUnavailable(rpcNodeCount())
                            } else {
                                Log.d(LIGHT_FALLBACK_TAG, "Every RPC node of ${chain.value.name} failed, using the light client")
                                light = lightClientNode(url).also { emit(it) }
                            }
                        }
                    }
                }

                FallbackEvent.Tick -> {
                    val now = System.currentTimeMillis()
                    if (policy.onTick(rpcNodeCount(), now)) {
                        Log.d(LIGHT_FALLBACK_TAG, "${chain.value.name}: retrying RPC")
                        emit(latestRpc)
                    }
                    if (policy.shouldReleaseLight(now)) {
                        Log.d(LIGHT_FALLBACK_TAG, "${chain.value.name}: RPC is stable, releasing the light client")
                        light = null
                        lightClients.release(chain.value.id)
                    }
                }
            }
        }
    }

    private fun lightClientNode(url: String) = NodeWithSaturatedUrl(
        node = Chain.Node(
            chainId = chain.value.id,
            unformattedUrl = url,
            name = "Light client",
            orderId = Int.MAX_VALUE,
            isCustom = false,
        ),
        saturatedUrl = url,
    )

    private fun State.urlOrNull(): String? = when (this) {
        is State.WaitingForReconnect -> url
        is State.Connecting -> url
        is State.Connected -> url
        State.Disconnected -> null
        is State.Paused -> url
    }

    override fun onRpcResponseReceived(rpcResponse: RpcResponse): ResponseDelivery {
        val error = rpcResponse.error

        return if (error != null && error.code in RATE_LIMIT_ERROR_CODES) {
            Log.d(LOG_TAG, "Received rate limit exceeded error code in rpc response. Switching to another node")

            responseRequiresNodeChangeFlow.tryEmit(Unit)

            ResponseDelivery.DROP
        } else {
            ResponseDelivery.DELIVER_TO_SENDER
        }
    }
}
