package io.novafoundation.nova.feature_wallet_impl.data.network.solana

import io.novafoundation.nova.common.data.network.UserAgent
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.model.SolanaGetBalanceResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.model.SolanaGetFeeForMessageResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.model.SolanaGetLatestBlockhashResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.model.SolanaRpcRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.model.SolanaSendTransactionResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

interface RetrofitSolanaApi {

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun getBalance(@Url url: String, @Body body: SolanaRpcRequest): SolanaGetBalanceResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun getLatestBlockhash(@Url url: String, @Body body: SolanaRpcRequest): SolanaGetLatestBlockhashResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun getFeeForMessage(@Url url: String, @Body body: SolanaRpcRequest): SolanaGetFeeForMessageResponse

    @POST
    @Headers(UserAgent.NOVA)
    suspend fun sendTransaction(@Url url: String, @Body body: SolanaRpcRequest): SolanaSendTransactionResponse
}
