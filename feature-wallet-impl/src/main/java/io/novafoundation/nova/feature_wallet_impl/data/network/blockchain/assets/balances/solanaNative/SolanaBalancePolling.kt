package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.solanaNative

import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val SOLANA_BALANCE_POLLING_INTERVAL_MS = 30_000L

/**
 * Solana's JSON-RPC has no push/subscription mechanism usable here (its websocket `accountSubscribe` would
 * need a persistent per-node WS connection this app's Bitcoin/Tron-style REST clients don't otherwise use) -
 * mirrors [io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.bitcoinNative.pollingBalanceFlow]'s
 * exact polling design instead.
 */
internal fun pollingBalanceFlow(
    intervalMs: Long = SOLANA_BALANCE_POLLING_INTERVAL_MS,
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
