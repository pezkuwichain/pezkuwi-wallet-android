package io.novafoundation.nova.feature_bridge_api.domain.model

import java.math.BigDecimal

/**
 * wUSDT Bridge Configuration
 *
 * This bridge enables 1:1 backed wUSDT on Pezkuwi Asset Hub,
 * backed by real USDT on Polkadot Asset Hub.
 */
data class BridgeConfig(
    /** Bridge wallet address on Polkadot Asset Hub (for deposits) */
    val polkadotDepositAddress: String,

    /** Bridge wallet address on Pezkuwi Asset Hub */
    val pezkuwiAddress: String,

    /** USDT Asset ID on Polkadot Asset Hub */
    val polkadotUsdtAssetId: Int,

    /** wUSDT Asset ID on Pezkuwi Asset Hub */
    val pezkuwiWusdtAssetId: Int,

    /** Minimum deposit amount in USDT */
    val minDeposit: BigDecimal,

    /** Minimum withdrawal amount in USDT */
    val minWithdraw: BigDecimal,

    /** Bridge fee in basis points (e.g., 10 = 0.1%) */
    val feeBasisPoints: Int
) {
    companion object {
        val DEFAULT = BridgeConfig(
            polkadotDepositAddress = "16dSTc3BexjQKiPta7yNncF8nio4YgDQiPbudHzkuh7XJi8K",
            pezkuwiAddress = "5Hh9KGn7oBTvtBPNcUvNeTQyw6oQrNfGdtsRU11QMc618Rse",
            polkadotUsdtAssetId = 1984,
            pezkuwiWusdtAssetId = 1000,
            minDeposit = BigDecimal("10"),
            minWithdraw = BigDecimal("10"),
            feeBasisPoints = 10
        )
    }

    /** Fee percentage as a human-readable string */
    val feePercentage: String
        get() = "${feeBasisPoints.toDouble() / 100}%"

    /** Calculate fee for a given amount */
    fun calculateFee(amount: BigDecimal): BigDecimal {
        return amount.multiply(BigDecimal(feeBasisPoints)).divide(BigDecimal(10000))
    }

    /** Calculate net amount after fee */
    fun calculateNetAmount(amount: BigDecimal): BigDecimal {
        return amount.subtract(calculateFee(amount))
    }
}
