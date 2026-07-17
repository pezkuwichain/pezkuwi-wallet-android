package io.novafoundation.nova.runtime.multiNetwork.connection.node.healthState

import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private const val GET_HEALTH_REQUEST = """{"jsonrpc":"2.0","id":1,"method":"getHealth"}"""

/**
 * Solana speaks its own JSON-RPC over plain HTTP POST, not Ethereum JSON-RPC/WS or a Tron/Bitcoin-style
 * plain-GET REST API - see [TronNodeHealthStateTester]/[BitcoinNodeHealthStateTester]'s docs for the same
 * rationale. `getHealth` is Solana's own dedicated liveness-check method, purpose-built for exactly this -
 * needs no account/address context, cheap on the node's side.
 */
class SolanaNodeHealthStateTester(
    private val node: Chain.Node,
    private val httpClient: OkHttpClient,
) : NodeHealthStateTester {

    @OptIn(ExperimentalTime::class)
    override suspend fun testNodeHealthState(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(node.unformattedUrl)
                .post(GET_HEALTH_REQUEST.toRequestBody(JSON_MEDIA_TYPE))
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
