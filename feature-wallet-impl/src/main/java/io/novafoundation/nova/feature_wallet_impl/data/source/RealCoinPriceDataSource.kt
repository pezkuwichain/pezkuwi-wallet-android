package io.novafoundation.nova.feature_wallet_impl.data.source

import io.novafoundation.nova.common.data.network.HttpExceptionHandler
import io.novafoundation.nova.common.utils.KeyMutex
import io.novafoundation.nova.common.utils.asQueryParam
import io.novafoundation.nova.common.utils.orZero
import io.novafoundation.nova.feature_currency_api.domain.model.Currency
import io.novafoundation.nova.feature_wallet_api.data.network.priceApi.CoingeckoApi
import io.novafoundation.nova.feature_wallet_api.data.network.priceApi.ProxyPriceApi
import io.novafoundation.nova.feature_wallet_api.data.repository.PricePeriod
import io.novafoundation.nova.feature_wallet_api.data.source.CoinPriceRemoteDataSource
import io.novafoundation.nova.feature_wallet_api.domain.model.CoinRateChange
import io.novafoundation.nova.feature_wallet_api.domain.model.HistoricalCoinRate
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

private const val PRICE_ID_HEZ = "hezkurd"
private const val PRICE_ID_PEZ = "pezkuwi"
private const val PRICE_ID_DOT = "polkadot"
private val HEZ_DOT_DIVISOR = BigDecimal(3)
private val PEZ_DOT_DIVISOR = BigDecimal(10)

class RealCoinPriceDataSource(
    private val priceApi: ProxyPriceApi,
    private val coingeckoApi: CoingeckoApi,
    private val httpExceptionHandler: HttpExceptionHandler
) : CoinPriceRemoteDataSource {

    private val mutex = KeyMutex()

    override suspend fun getLastCoinPriceRange(priceId: String, currency: Currency, range: PricePeriod): List<HistoricalCoinRate> {
        val key = "${priceId}_${currency.id}_$range"
        val response = mutex.withKeyLock(key) {
            val days = mapRangeToDays(range)

            priceApi.getLastCoinRange(priceId, currency.coingeckoId, days)
        }

        return response.prices.map { (timestampRaw, rateRaw) ->
            HistoricalCoinRate(
                timestamp = timestampRaw.toLong().milliseconds.inWholeSeconds,
                rate = rateRaw
            )
        }
    }

    override suspend fun getCoinRates(priceIds: Set<String>, currency: Currency): Map<String, CoinRateChange?> {
        // Ensure DOT is included for fallback calculation if HEZ or PEZ is requested
        val needsFallback = priceIds.contains(PRICE_ID_HEZ) || priceIds.contains(PRICE_ID_PEZ)
        val allPriceIds = if (needsFallback) priceIds + PRICE_ID_DOT else priceIds

        val sortedPriceIds = allPriceIds.toList().sorted()
        val rawRates = apiCall { coingeckoApi.getAssetPrice(sortedPriceIds.asQueryParam(), currency = currency.coingeckoId, includeRateChange = true) }

        val rates = rawRates.mapValues {
            val price = it.value[currency.coingeckoId].orZero()
            val recentRate = it.value[CoingeckoApi.getRecentRateFieldName(currency.coingeckoId)].orZero()
            CoinRateChange(
                recentRate.toBigDecimal(),
                price.toBigDecimal()
            )
        }.toMutableMap()

        // Apply fallback pricing for HEZ and PEZ if their prices are zero or missing
        val dotRate = rates[PRICE_ID_DOT]
        if (dotRate != null && dotRate.rate > BigDecimal.ZERO) {
            // HEZ fallback: 1 HEZ = DOT / 3
            if (priceIds.contains(PRICE_ID_HEZ)) {
                val hezRate = rates[PRICE_ID_HEZ]
                if (hezRate == null || hezRate.rate <= BigDecimal.ZERO) {
                    rates[PRICE_ID_HEZ] = CoinRateChange(
                        recentRateChange = dotRate.recentRateChange,
                        rate = dotRate.rate.divide(HEZ_DOT_DIVISOR, 10, java.math.RoundingMode.HALF_UP)
                    )
                }
            }

            // PEZ fallback: 1 PEZ = DOT / 10
            if (priceIds.contains(PRICE_ID_PEZ)) {
                val pezRate = rates[PRICE_ID_PEZ]
                if (pezRate == null || pezRate.rate <= BigDecimal.ZERO) {
                    rates[PRICE_ID_PEZ] = CoinRateChange(
                        recentRateChange = dotRate.recentRateChange,
                        rate = dotRate.rate.divide(PEZ_DOT_DIVISOR, 10, java.math.RoundingMode.HALF_UP)
                    )
                }
            }
        }

        // Return only requested priceIds
        return rates.filterKeys { it in priceIds }
    }

    override suspend fun getCoinRate(priceId: String, currency: Currency): CoinRateChange? {
        return getCoinRates(priceIds = setOf(priceId), currency = currency)
            .values
            .firstOrNull()
    }

    private suspend fun <T> apiCall(block: suspend () -> T): T = httpExceptionHandler.wrap(block)

    private fun mapRangeToDays(range: PricePeriod) = when (range) {
        PricePeriod.DAY -> "1"
        PricePeriod.WEEK -> "7"
        PricePeriod.MONTH -> "30"
        PricePeriod.YEAR -> "365"
        PricePeriod.MAX -> "max"
    }
}
