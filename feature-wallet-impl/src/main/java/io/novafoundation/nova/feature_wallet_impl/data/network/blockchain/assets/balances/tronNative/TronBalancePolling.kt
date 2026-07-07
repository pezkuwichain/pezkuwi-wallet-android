package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.tronNative

import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TRON_BALANCE_POLLING_INTERVAL_MS = 30_000L

/**
 * TronGrid is a plain REST API with no push/subscription mechanism (unlike Ethereum nodes, which expose a
 * `newHeads`-style websocket subscription EVM balance sync piggybacks on). So balance updates for Tron-based
 * assets are polled instead of pushed: fetch immediately, then re-fetch on an interval, only emitting when the
 * balance actually changed.
 */
internal fun pollingBalanceFlow(
    intervalMs: Long = TRON_BALANCE_POLLING_INTERVAL_MS,
    fetch: suspend () -> Balance
): Flow<Balance> = flow {
    var lastEmitted: Balance? = null

    while (true) {
        val latest = fetch()

        if (latest != lastEmitted) {
            lastEmitted = latest
            emit(latest)
        }

        delay(intervalMs)
    }
}
