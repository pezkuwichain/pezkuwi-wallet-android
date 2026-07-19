package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.bitcoinNative

import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val BITCOIN_BALANCE_POLLING_INTERVAL_MS = 30_000L

/**
 * mempool.space is a plain REST API with no push/subscription mechanism, same as TronGrid - see
 * `TronBalancePolling.pollingBalanceFlow`'s doc for the full rationale this mirrors.
 */
internal fun pollingBalanceFlow(
    intervalMs: Long = BITCOIN_BALANCE_POLLING_INTERVAL_MS,
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
