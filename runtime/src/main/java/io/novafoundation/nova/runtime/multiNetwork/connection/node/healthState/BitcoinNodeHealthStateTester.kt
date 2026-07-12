package io.novafoundation.nova.runtime.multiNetwork.connection.node.healthState

import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

/**
 * mempool.space speaks a plain REST API, not Ethereum JSON-RPC - see [TronNodeHealthStateTester]'s doc for the
 * identical rationale this mirrors. `GET /blocks/tip/height` needs no account/address context and is cheap on
 * mempool.space's side, making it a good generic liveness ping.
 */
class BitcoinNodeHealthStateTester(
    private val node: Chain.Node,
    private val httpClient: OkHttpClient,
) : NodeHealthStateTester {

    @OptIn(ExperimentalTime::class)
    override suspend fun testNodeHealthState(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${node.unformattedUrl.trimEnd('/')}/blocks/tip/height")
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
