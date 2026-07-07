package io.novafoundation.nova.feature_wallet_impl.data.network.tron

import io.novafoundation.nova.common.data.network.UserAgent
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAccountResourceResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAccountResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAddressRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronBroadcastRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronBroadcastResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronChainParametersResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronCreateTransactionRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronTriggerContractRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronTriggerContractResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronUnsignedTransactionResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

interface RetrofitTronGridApi {

    @GET
    @Headers(UserAgent.NOVA)
    suspend fun getAccount(@Url url: String): TronAccountResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun createTransaction(@Url url: String, @Body body: TronCreateTransactionRequest): TronUnsignedTransactionResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun triggerConstantContract(@Url url: String, @Body body: TronTriggerContractRequest): TronTriggerContractResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun triggerSmartContract(@Url url: String, @Body body: TronTriggerContractRequest): TronTriggerContractResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun broadcastTransaction(@Url url: String, @Body body: TronBroadcastRequest): TronBroadcastResponse

    @GET
    @Headers(UserAgent.NOVA)
    suspend fun getChainParameters(@Url url: String): TronChainParametersResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun getAccountResource(@Url url: String, @Body body: TronAddressRequest): TronAccountResourceResponse
}
