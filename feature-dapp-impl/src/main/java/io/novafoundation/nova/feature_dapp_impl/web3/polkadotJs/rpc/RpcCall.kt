package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser

/**
 * `{method, params}` of an RPC request in [message], or null when the page sent something else.
 * Params stay JSON as the page wrote them: parsed into Kotlin values a block number would reach
 * the node as `123.0`.
 */
fun rpcCall(message: String): Pair<String, List<JsonElement>>? {
    val request = runCatching { JsonParser.parseString(message).asJsonObject.get("request") }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val method = request.get("method")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    val params = when (val raw = request.get("params")) {
        null, is JsonNull -> emptyList()
        is JsonArray -> raw.toList()
        else -> return null
    }
    return method to params
}
