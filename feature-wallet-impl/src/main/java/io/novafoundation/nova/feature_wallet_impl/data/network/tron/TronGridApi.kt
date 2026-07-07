package io.novafoundation.nova.feature_wallet_impl.data.network.tron

import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import java.math.BigInteger

interface TronGridApi {

    suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance

    suspend fun fetchTrc20Balance(baseUrl: String, address: String, contractAddress: String): Balance
}

class RealTronGridApi(
    private val retrofitApi: RetrofitTronGridApi
) : TronGridApi {

    override suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance {
        val accountData = fetchAccountData(baseUrl, address) ?: return BigInteger.ZERO

        return accountData.balance?.toBigInteger() ?: BigInteger.ZERO
    }

    override suspend fun fetchTrc20Balance(baseUrl: String, address: String, contractAddress: String): Balance {
        val accountData = fetchAccountData(baseUrl, address) ?: return BigInteger.ZERO

        val rawBalance = accountData.trc20.orEmpty()
            .firstNotNullOfOrNull { entry -> entry[contractAddress] }

        return rawBalance?.toBigIntegerOrNull() ?: BigInteger.ZERO
    }

    private suspend fun fetchAccountData(baseUrl: String, address: String) = retrofitApi.getAccount(
        url = accountUrl(baseUrl, address)
    ).data?.firstOrNull()

    private fun accountUrl(baseUrl: String, address: String): String {
        return "${baseUrl.trimEnd('/')}/v1/accounts/$address"
    }
}
