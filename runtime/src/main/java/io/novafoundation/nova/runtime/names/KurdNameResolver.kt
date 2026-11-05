package io.novafoundation.nova.runtime.names

import android.util.Log
import io.novafoundation.nova.common.data.network.runtime.calls.GetFinalizedHeadRequest
import io.novafoundation.nova.common.utils.awaitConnected
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.multiNetwork.connection.ConnectionSecrets
import io.novafoundation.nova.runtime.multiNetwork.connection.light.LightClients
import io.novafoundation.nova.runtime.multiNetwork.connection.saturateNodeUrl
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.wsrpc.SocketService
import io.novasama.substrate_sdk_android.wsrpc.executeAsync
import io.novasama.substrate_sdk_android.wsrpc.mappers.nonNull
import io.novasama.substrate_sdk_android.wsrpc.mappers.pojo
import io.novasama.substrate_sdk_android.wsrpc.request.runtime.RuntimeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.net.URI
import javax.inject.Provider

/**
 * Resolves `.kurd` names from `Navnisan` on [chainId]: through the light client first, so a name
 * is proven rather than reported, and only when it cannot answer through two RPC nodes that must
 * agree (architecture §5.2).
 *
 * The chain's own connection is RPC first and moves to the light client only once every node
 * fails, so it is not used here: a name read through it would be the node's word.
 */
class RealKurdNameResolver(
    private val chainId: ChainId,
    private val lightClients: LightClients,
    private val socketProvider: Provider<SocketService>,
    private val chainRegistry: ChainRegistry,
    private val connectionSecrets: ConnectionSecrets,
) : KurdNameResolver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val leaseMutex = Mutex()
    private var leaseRelease: Job? = null

    override suspend fun resolve(name: KurdName): KurdResolution {
        val light = runCatching { readThroughLightClient(name) }
            .onFailure { Log.w(TAG, "Light client could not resolve $name", it) }
            .getOrNull()
        if (light != null) return judge(name, light, verified = true)

        val urls = rpcUrls()
        if (urls.size < AGREEING_NODES) return refuseOrGiveUp(name)

        val lookups = runCatching {
            coroutineScope {
                urls.take(AGREEING_NODES).map { url -> async { withSocket(url) { NameLookup.read(SocketStorageReader(it), name) } } }.awaitAll()
            }
        }.onFailure { Log.w(TAG, "RPC nodes could not resolve $name", it) }.getOrNull() ?: return refuseOrGiveUp(name)

        val first = lookups.first()
        if (!lookups.all { it.agreesWith(first) }) return refuseOrGiveUp(name)

        return judge(name, first, verified = false)
    }

    private suspend fun readThroughLightClient(name: KurdName): NameLookup {
        val url = holdLightClient() ?: error("Light client unavailable")
        return withTimeout(LIGHT_TIMEOUT_MILLIS) {
            withSocket(url) { NameLookup.read(SocketStorageReader(it), name) }
        }
    }

    /**
     * Keeps the names chain syncing between lookups, so a second name does not wait for a cold
     * start, and lets it go once nothing has been resolved for a while.
     */
    private suspend fun holdLightClient(): String? = leaseMutex.withLock {
        leaseRelease?.cancel()
        val url = lightClients.url(chainId, holder = this)
        leaseRelease = scope.launch {
            delay(LIGHT_IDLE_MILLIS)
            lightClients.release(chainId, holder = this@RealKurdNameResolver)
        }
        url
    }

    /** RPC node URLs of the names chain, at most one per host, so agreement is not one server twice. */
    private suspend fun rpcUrls(): List<String> {
        val chain = chainRegistry.getChain(chainId)
        return chain.nodes.nodes
            .filter { it.connectionType == Chain.Node.ConnectionType.WSS }
            .mapNotNull { it.saturateNodeUrl(connectionSecrets)?.saturatedUrl }
            .distinctBy { runCatching { URI(it).host }.getOrNull() ?: it }
    }

    private suspend fun <T> withSocket(url: String, block: suspend (SocketService) -> T): T {
        val socket = socketProvider.get()
        socket.start(url)
        return try {
            socket.awaitConnected()
            block(socket)
        } finally {
            socket.stop()
        }
    }

    private class SocketStorageReader(private val socket: SocketService) : NameStorageReader {

        override suspend fun finalizedHead(): String = socket.executeAsync(GetFinalizedHeadRequest, mapper = pojo<String>().nonNull())

        override suspend fun storage(key: ByteArray, at: String): ByteArray? {
            val request = RuntimeRequest(method = "state_getStorage", params = listOf(key.toHexString(withPrefix = true), at))
            return socket.executeAsync(request, mapper = pojo<String>()).result?.fromHex()
        }

        override suspend fun blockNumber(at: String): Long? {
            val header = socket.executeAsync(RuntimeRequest(method = "chain_getHeader", params = listOf(at))).result as? Map<*, *>
            return (header?.get("number") as? String)?.removePrefix("0x")?.toLongOrNull(16)
        }
    }

    companion object {

        /**
         * D18: a proven answer stands; an unproven one opens only for a name nobody could take for an
         * institution's, and with a warning.
         */
        internal fun judge(name: KurdName, lookup: NameLookup, verified: Boolean): KurdResolution = when (lookup) {
            NameLookup.NotFound, NameLookup.Lapsed -> KurdResolution.NotFound(name, verified)
            is NameLookup.Found -> when {
                verified -> KurdResolution.Resolved(name, lookup.record, lookup.status, verified = true, block = lookup.block)
                mustBeVerified(name, lookup) -> KurdResolution.Refused(name)
                else -> KurdResolution.Resolved(name, lookup.record, lookup.status, verified = false, block = lookup.block)
            }
        }

        /** No answer at all: a zone name is known to be institutional without one. */
        internal fun refuseOrGiveUp(name: KurdName) = if (name.top in ZONES) KurdResolution.Refused(name) else KurdResolution.Unavailable(name)

        private fun mustBeVerified(name: KurdName, found: NameLookup.Found): Boolean {
            return name.top in ZONES || found.locked || found.record.isOfficial || found.record.isInstitutional
        }

        /** Zones created at genesis: every name under them belongs to an institution (decisions D6, D13). */
        val ZONES = setOf("gov", "edu", "mil", "int", "org", "net")

        private const val AGREEING_NODES = 2

        /** A cold light client within the acceptance target (architecture §1, criterion 2). */
        private const val LIGHT_TIMEOUT_MILLIS = 30_000L
        private const val LIGHT_IDLE_MILLIS = 5 * 60_000L

        private const val TAG = "KurdNameResolver"
    }
}
