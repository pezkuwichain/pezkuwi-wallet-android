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
import io.novafoundation.nova.runtime.BuildConfig
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

// TronGrid's anonymous rate limit is aggressive enough to surface under normal, human-paced app usage (see
// RealTronGridApi.retryOn429's doc comment) - a free API key from trongrid.io raises it substantially. Sent on
// every request rather than only when a 429 is hit, since the point is to avoid needing the retry in the first
// place, not just to have a fallback.
private const val TRON_API_KEY_HEADER = "TRON-PRO-API-KEY: " + BuildConfig.TRONGRID_API_KEY

interface RetrofitTronGridApi {

    @GET
    @Headers(UserAgent.NOVA, TRON_API_KEY_HEADER)
    suspend fun getAccount(@Url url: String): TronAccountResponse

    @POST
    @Headers(UserAgent.NOVA, TRON_API_KEY_HEADER)
    suspend fun createTransaction(@Url url: String, @Body body: TronCreateTransactionRequest): TronUnsignedTransactionResponse

    @POST
    @Headers(UserAgent.NOVA, TRON_API_KEY_HEADER)
    suspend fun triggerConstantContract(@Url url: String, @Body body: TronTriggerContractRequest): TronTriggerContractResponse

    @POST
    @Headers(UserAgent.NOVA, TRON_API_KEY_HEADER)
    suspend fun triggerSmartContract(@Url url: String, @Body body: TronTriggerContractRequest): TronTriggerContractResponse

    @POST
    @Headers(UserAgent.NOVA, TRON_API_KEY_HEADER)
    suspend fun broadcastTransaction(@Url url: String, @Body body: TronBroadcastRequest): TronBroadcastResponse

    @GET
    @Headers(UserAgent.NOVA, TRON_API_KEY_HEADER)
    suspend fun getChainParameters(@Url url: String): TronChainParametersResponse

    @POST
    @Headers(UserAgent.NOVA, TRON_API_KEY_HEADER)
    suspend fun getAccountResource(@Url url: String, @Body body: TronAddressRequest): TronAccountResourceResponse
}
