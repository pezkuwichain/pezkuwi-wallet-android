package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.model

import com.google.gson.annotations.SerializedName

/**
 * Response shape of mempool.space's `GET /address/{address}`.
 *
 * `chainStats` reflects only confirmed on-chain activity; `mempoolStats` (unconfirmed) is deliberately not
 * used for balance - matching the exchange's proven 2-confirmation-required posture for this same API.
 */
class BitcoinAddressResponse(
    @SerializedName("chain_stats")
    val chainStats: BitcoinAddressStats? = null,
)

class BitcoinAddressStats(
    @SerializedName("funded_txo_sum")
    val fundedTxoSum: Long? = null,

    @SerializedName("spent_txo_sum")
    val spentTxoSum: Long? = null,
)
