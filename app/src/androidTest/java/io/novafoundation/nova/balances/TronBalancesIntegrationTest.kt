package io.novafoundation.nova.balances

import io.novafoundation.nova.feature_wallet_impl.data.network.tron.RealTronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.RetrofitTronGridApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.math.BigInteger

/**
 * Tron is a REST API (TronGrid), not a Substrate runtime - it has no ChainConnection/RuntimeProvider and isn't
 * reachable through [BalancesIntegrationTest]'s chainRegistry-based mechanism at all. This exercises the exact
 * same [io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi] the production app uses for
 * balance reads (see TronNativeAssetBalance/Trc20AssetBalance), just via a standalone Retrofit client instead of
 * the full DI graph, since TronGridApi isn't exposed through a public feature API for tests to reach.
 *
 * Test account is the mainnet Founder's Tron address, verified live via TronGrid's public API on 2026-07-09 to
 * hold a substantial non-zero balance of both native TRX and TRC-20 USDT - not a guessed or empty account.
 */
class TronBalancesIntegrationTest {

    private val testAddress = "TDGZ4GfvCRe1d8oksj8fBD77ZHw4bkCPBA"
    private val usdtContractAddress = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
    private val baseUrl = "https://api.trongrid.io"

    private val maxAmount = BigInteger.valueOf(10).pow(30)

    private val tronGridApi = run {
        val retrofit = Retrofit.Builder()
            .client(OkHttpClient.Builder().build())
            .baseUrl(baseUrl)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        RealTronGridApi(retrofit.create(RetrofitTronGridApi::class.java))
    }

    // TronGrid's public (no API key) endpoint rate-limits aggressively, and this test now runs on every PR
    // (see balances_test.yml) in addition to its own 2 calls back-to-back - a bare 429 previously failed the
    // whole run for a transient, infrastructure-level reason unrelated to whether the wallet's code is correct.
    // Retry with backoff instead of just tolerating the flake.
    private suspend fun <T> retryOn429(maxAttempts: Int = 4, block: suspend () -> T): T {
        repeat(maxAttempts - 1) { attempt ->
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code() != 429) throw e
                delay(2_000L * (attempt + 1))
            }
        }
        return block()
    }

    @Test
    fun testNativeTrxBalanceLoading() = runBlocking {
        val freeBalance = retryOn429 { tronGridApi.fetchNativeBalance(baseUrl, testAddress) }

        assertTrue("TRX balance: $freeBalance is less than $maxAmount", maxAmount > freeBalance)
        assertTrue("TRX balance: $freeBalance is greater than 0", BigInteger.ZERO < freeBalance)
    }

    @Test
    fun testTrc20UsdtBalanceLoading() = runBlocking {
        val freeBalance = retryOn429 { tronGridApi.fetchTrc20Balance(baseUrl, testAddress, usdtContractAddress) }

        assertTrue("USDT-TRC20 balance: $freeBalance is less than $maxAmount", maxAmount > freeBalance)
        assertTrue("USDT-TRC20 balance: $freeBalance is greater than 0", BigInteger.ZERO < freeBalance)
    }
}
