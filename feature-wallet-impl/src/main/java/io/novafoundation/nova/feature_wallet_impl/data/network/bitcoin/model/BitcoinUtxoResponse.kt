package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.model

/** Response shape of mempool.space's `GET /address/{address}/utxo`. */
class BitcoinUtxoResponse(
    val txid: String,
    val vout: Int,
    val value: Long,
    val status: BitcoinUtxoStatus? = null,
)

class BitcoinUtxoStatus(
    val confirmed: Boolean = false,
)

/** Response shape of mempool.space's `GET /v1/fees/recommended`, all values in sat/vB. */
class BitcoinFeeEstimateResponse(
    val fastestFee: Long? = null,
    val halfHourFee: Long? = null,
    val hourFee: Long? = null,
    val economyFee: Long? = null,
    val minimumFee: Long? = null,
)
