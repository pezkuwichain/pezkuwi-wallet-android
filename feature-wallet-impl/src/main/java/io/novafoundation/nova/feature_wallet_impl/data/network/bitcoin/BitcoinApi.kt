package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin

import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.math.BigInteger

/** A single unspent output, as needed to select inputs and build a transaction. */
data class BitcoinUtxo(
    val txid: String,
    val vout: Int,
    val valueSat: Long,
    val confirmed: Boolean,
)

interface BitcoinApi {

    suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance

    suspend fun fetchUtxos(baseUrl: String, address: String): List<BitcoinUtxo>

    /** sat/vB, mempool.space's ~30-minute-confirmation estimate - a balanced default, neither cheapest nor fastest. */
    suspend fun fetchRecommendedFeeRateSatPerVbyte(baseUrl: String): Long

    /** @param rawTxHex fully signed raw transaction, hex-encoded. @return the broadcast transaction's txid. */
    suspend fun broadcastTransaction(baseUrl: String, rawTxHex: String): String
}

class RealBitcoinApi(
    private val retrofitApi: RetrofitBitcoinApi
) : BitcoinApi {

    override suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance {
        val stats = retryOn429 { retrofitApi.getAddress(addressUrl(baseUrl, address)) }.chainStats
            ?: return BigInteger.ZERO

        val funded = stats.fundedTxoSum ?: 0L
        val spent = stats.spentTxoSum ?: 0L

        return (funded - spent).toBigInteger().coerceAtLeast(BigInteger.ZERO)
    }

    override suspend fun fetchUtxos(baseUrl: String, address: String): List<BitcoinUtxo> {
        val response = retryOn429 { retrofitApi.getUtxos("${baseUrl.trimEnd('/')}/address/$address/utxo") }

        return response.map { utxo ->
            BitcoinUtxo(
                txid = utxo.txid,
                vout = utxo.vout,
                valueSat = utxo.value,
                confirmed = utxo.status?.confirmed == true
            )
        }
    }

    override suspend fun fetchRecommendedFeeRateSatPerVbyte(baseUrl: String): Long {
        val fees = retryOn429 { retrofitApi.getRecommendedFees("${baseUrl.trimEnd('/')}/v1/fees/recommended") }

        return fees.halfHourFee ?: fees.hourFee ?: fees.fastestFee ?: 1L
    }

    override suspend fun broadcastTransaction(baseUrl: String, rawTxHex: String): String {
        return retryOn429 { retrofitApi.broadcastTransaction("${baseUrl.trimEnd('/')}/tx", rawTxHex) }.trim()
    }

    private fun addressUrl(baseUrl: String, address: String): String {
        return "${baseUrl.trimEnd('/')}/address/$address"
    }

    private suspend fun <T> retryOn429(maxAttempts: Int = 4, block: suspend () -> T): T {
        repeat(maxAttempts - 1) { attempt ->
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code() != 429) throw e
                delay(1_000L * (attempt + 1))
            }
        }
        return block()
    }
}
