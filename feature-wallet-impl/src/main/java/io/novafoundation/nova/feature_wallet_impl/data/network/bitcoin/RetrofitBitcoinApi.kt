package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin

import io.novafoundation.nova.common.data.network.UserAgent
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.model.BitcoinAddressResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

interface RetrofitBitcoinApi {

    @GET
    @Headers(UserAgent.NOVA)
    suspend fun getAddress(@Url url: String): BitcoinAddressResponse
}
