package io.novafoundation.nova.runtime.multiNetwork.connection.light

/**
 * When a chain's connection moves from its RPC nodes to the embedded light client, and back.
 *
 * RPC comes first: a light client puts load on the peer-to-peer nodes that RPC does not, and
 * those nodes have a fixed number of light client slots. Once every RPC node has failed in a row,
 * the light client takes over. RPC is retried after [initialBackoffMillis], doubling up to
 * [maxBackoffMillis] while it keeps failing, and takes back the connection once it connects.
 *
 * Not thread-safe: one connection feeds it its events in order.
 */
class LightFallbackPolicy(
    private val initialBackoffMillis: Long = 60_000,
    private val maxBackoffMillis: Long = 15 * 60_000,
) {

    enum class Source { RPC, LIGHT }

    var source = Source.RPC
        private set

    private var consecutiveRpcFailures = 0
    private var backoffMillis = initialBackoffMillis
    private var rpcRetryAt: Long? = null

    /** An RPC node failed. True when the light client should take over now. */
    fun onRpcFailure(rpcNodeCount: Int, nowMillis: Long): Boolean {
        if (source != Source.RPC) return false

        consecutiveRpcFailures++
        if (consecutiveRpcFailures < rpcNodeCount.coerceAtLeast(1)) return false

        source = Source.LIGHT
        rpcRetryAt = nowMillis + backoffMillis
        backoffMillis = (backoffMillis * 2).coerceAtMost(maxBackoffMillis)
        return true
    }

    fun onRpcConnected() {
        source = Source.RPC
        consecutiveRpcFailures = 0
        backoffMillis = initialBackoffMillis
        rpcRetryAt = null
    }

    /**
     * True when RPC should be tried again now. One more RPC failure then hands the connection
     * straight back to the light client, without walking every node again.
     */
    fun onTick(rpcNodeCount: Int, nowMillis: Long): Boolean {
        val retryAt = rpcRetryAt ?: return false
        if (source != Source.LIGHT || nowMillis < retryAt) return false

        returnToRpc(rpcNodeCount)
        return true
    }

    /** The light client could not start or its connection failed: RPC is all there is. */
    fun onLightUnavailable(rpcNodeCount: Int) {
        if (source == Source.LIGHT) returnToRpc(rpcNodeCount)
    }

    private fun returnToRpc(rpcNodeCount: Int) {
        source = Source.RPC
        consecutiveRpcFailures = (rpcNodeCount - 1).coerceAtLeast(0)
        rpcRetryAt = null
    }
}
