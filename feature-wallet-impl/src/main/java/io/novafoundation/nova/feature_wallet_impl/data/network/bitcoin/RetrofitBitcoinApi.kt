package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin

import io.novafoundation.nova.common.data.network.UserAgent
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.model.BitcoinAddressResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.model.BitcoinFeeEstimateResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.model.BitcoinUtxoResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

interface RetrofitBitcoinApi {

    @GET
    @Headers(UserAgent.NOVA)
    suspend fun getAddress(@Url url: String): BitcoinAddressResponse

    @GET
    @Headers(UserAgent.NOVA)
    suspend fun getUtxos(@Url url: String): List<BitcoinUtxoResponse>

    @GET
    @Headers(UserAgent.NOVA)
    suspend fun getRecommendedFees(@Url url: String): BitcoinFeeEstimateResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun broadcastTransaction(@Url url: String, @Body rawTxHex: String): String
}
