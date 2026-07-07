package io.novafoundation.nova.feature_wallet_impl.data.network.tron

import io.novafoundation.nova.common.data.network.UserAgent
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAccountResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

interface RetrofitTronGridApi {

    @GET
    @Headers(UserAgent.NOVA)
    suspend fun getAccount(@Url url: String): TronAccountResponse
}
