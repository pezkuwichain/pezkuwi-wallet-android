package io.novafoundation.nova.feature_wallet_impl.data.network.solana

import io.novafoundation.nova.common.utils.Base58
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.model.SolanaRpcRequest
import java.math.BigInteger
import java.util.Base64

class SolanaApiException(code: Int, message: String) : Exception("Solana RPC error $code: $message")

interface SolanaApi {

    suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance

    /** @return the 32 raw bytes of the cluster's most recent blockhash, ready to embed in a [io.novafoundation.nova.common.utils.SolanaTransaction] message. */
    suspend fun fetchLatestBlockhash(baseUrl: String): ByteArray

    /** @return the exact fee (lamports) the cluster will charge for this specific compiled [message], per `getFeeForMessage`. */
    suspend fun calculateFeeForMessage(baseUrl: String, message: ByteArray): BigInteger

    /** @param signedTransaction a fully-signed, serialized transaction (see [io.novafoundation.nova.common.utils.SolanaTransaction.serializeSigned]). @return the broadcast transaction's signature (its id/hash). */
    suspend fun broadcastTransaction(baseUrl: String, signedTransaction: ByteArray): String
}

class RealSolanaApi(
    private val retrofitApi: RetrofitSolanaApi
) : SolanaApi {

    override suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance {
        val request = SolanaRpcRequest(method = "getBalance", params = listOf(address))
        val response = retrofitApi.getBalance(baseUrl, request)

        response.error?.let { throw SolanaApiException(it.code, it.message) }

        return response.result?.value?.toBigInteger() ?: BigInteger.ZERO
    }

    override suspend fun fetchLatestBlockhash(baseUrl: String): ByteArray {
        val request = SolanaRpcRequest(method = "getLatestBlockhash")
        val response = retrofitApi.getLatestBlockhash(baseUrl, request)

        response.error?.let { throw SolanaApiException(it.code, it.message) }

        val blockhashBase58 = response.result?.value?.blockhash
            ?: throw SolanaApiException(-1, "getLatestBlockhash returned no result")

        return Base58.decode(blockhashBase58)
    }

    override suspend fun calculateFeeForMessage(baseUrl: String, message: ByteArray): BigInteger {
        val messageBase64 = Base64.getEncoder().encodeToString(message)
        val request = SolanaRpcRequest(method = "getFeeForMessage", params = listOf(messageBase64, mapOf("encoding" to "base64")))
        val response = retrofitApi.getFeeForMessage(baseUrl, request)

        response.error?.let { throw SolanaApiException(it.code, it.message) }

        val feeLamports = response.result?.value
            ?: throw SolanaApiException(-1, "getFeeForMessage could not price this message (unknown/expired blockhash)")

        return feeLamports.toBigInteger()
    }

    override suspend fun broadcastTransaction(baseUrl: String, signedTransaction: ByteArray): String {
        val txBase64 = Base64.getEncoder().encodeToString(signedTransaction)
        val request = SolanaRpcRequest(method = "sendTransaction", params = listOf(txBase64, mapOf("encoding" to "base64")))
        val response = retrofitApi.sendTransaction(baseUrl, request)

        response.error?.let { throw SolanaApiException(it.code, it.message) }

        return response.result ?: throw SolanaApiException(-1, "sendTransaction returned no signature")
    }
}
