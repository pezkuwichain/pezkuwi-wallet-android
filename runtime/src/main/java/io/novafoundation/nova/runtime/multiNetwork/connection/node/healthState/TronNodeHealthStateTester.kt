package io.novafoundation.nova.runtime.multiNetwork.connection.node.healthState

import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

/**
 * TronGrid speaks a plain REST API, not Ethereum JSON-RPC - reusing [EthereumNodeHealthStateTester] against it
 * (as this codebase used to, before Tron nodes were included in health checks at all) would send an
 * `eth_getBalance` call TronGrid doesn't understand, always reporting the node as unreachable regardless of its
 * actual health. `GET /wallet/getchainparameters` needs no account/address context and is cheap on TronGrid's
 * side, making it a good generic liveness ping - same endpoint this codebase already uses elsewhere
 * (`TronGridApi.getChainParameters`), just called directly here since `runtime` cannot depend on
 * `feature-wallet-impl` (wrong direction) to reuse that Retrofit interface.
 */
class TronNodeHealthStateTester(
    private val node: Chain.Node,
    private val httpClient: OkHttpClient,
) : NodeHealthStateTester {

    @OptIn(ExperimentalTime::class)
    override suspend fun testNodeHealthState(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${node.unformattedUrl.trimEnd('/')}/wallet/getchainparameters")
                .build()

            val duration = measureTime {
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                }
            }

            duration.inWholeMilliseconds
        }
    }
}
