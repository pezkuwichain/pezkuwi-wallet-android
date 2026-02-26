package io.novafoundation.nova.common.utils

import android.util.Log
import io.novasama.substrate_sdk_android.wsrpc.recovery.LinearReconnectStrategy
import io.novasama.substrate_sdk_android.wsrpc.recovery.ReconnectStrategy
import kotlinx.coroutines.delay

suspend inline fun <T> retryUntilDone(
    retryStrategy: ReconnectStrategy = LinearReconnectStrategy(step = 500L),
    maxAttempts: Int = Int.MAX_VALUE,
    block: () -> T,
): T {
    var attempt = 0

    while (true) {
        val blockResult = runCatching { block() }

        if (blockResult.isSuccess) {
            return blockResult.requireValue()
        } else {
            attempt++

            if (attempt >= maxAttempts) {
                throw blockResult.requireException()
            }

            Log.e("RetryUntilDone", "Failed to execute retriable operation (attempt $attempt):", blockResult.requireException())

            delay(retryStrategy.getTimeForReconnect(attempt))
        }
    }
}
