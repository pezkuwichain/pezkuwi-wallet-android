package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.tronNative

import android.util.Log
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TRON_BALANCE_POLLING_INTERVAL_MS = 30_000L
private const val LOG_TAG = "TronBalancePolling"

/**
 * TronGrid is a plain REST API with no push/subscription mechanism (unlike Ethereum nodes, which expose a
 * `newHeads`-style websocket subscription EVM balance sync piggybacks on). So balance updates for Tron-based
 * assets are polled instead of pushed: fetch immediately, then re-fetch on an interval, only emitting when the
 * balance actually changed.
 *
 * A failed fetch() must not escape this loop: any uncaught exception here cancels the whole flow permanently
 * (the collector - FullSyncPaymentUpdater - only logs and gives up, it doesn't resubscribe), which meant a
 * single transient failure (DNS hiccup, timeout, momentary connectivity loss during app cold start) could
 * silently and permanently blackhole a Tron asset - no balance write ever happens, so it never even gets a row
 * in the local DB and disappears from every UI surface with no visible error. Swallow and retry next interval
 * instead.
 */
internal fun pollingBalanceFlow(
    intervalMs: Long = TRON_BALANCE_POLLING_INTERVAL_MS,
    fetch: suspend () -> Balance
): Flow<Balance> = flow {
    var lastEmitted: Balance? = null

    while (true) {
        val latest = runCatching { fetch() }
            .onFailure { Log.e(LOG_TAG, "Tron balance fetch failed, will retry in ${intervalMs}ms", it) }
            .getOrNull()

        if (latest != null && latest != lastEmitted) {
            lastEmitted = latest
            emit(latest)
        }

        delay(intervalMs)
    }
}
