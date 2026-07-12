package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin

import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.math.BigInteger

interface BitcoinApi {

    suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance
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
