package io.novafoundation.nova.feature_bridge_api.domain.model

import java.math.BigDecimal

/**
 * Bridge status showing backing ratio and reserves
 */
data class BridgeStatus(
    /** Total USDT held in bridge wallet on Polkadot */
    val totalUsdtBacking: BigDecimal,

    /** Total wUSDT in circulation on Pezkuwi */
    val totalWusdtCirculating: BigDecimal,

    /** Bridge operational status */
    val isOperational: Boolean,

    /** Last sync timestamp */
    val lastSyncTimestamp: Long
) {
    /** Backing ratio (should be >= 100%) */
    val backingRatio: BigDecimal
        get() = if (totalWusdtCirculating > BigDecimal.ZERO) {
            totalUsdtBacking.divide(totalWusdtCirculating, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        } else {
            BigDecimal(100)
        }

    /** Reserve (excess USDT in bridge, i.e., collected fees) */
    val reserve: BigDecimal
        get() = totalUsdtBacking.subtract(totalWusdtCirculating).coerceAtLeast(BigDecimal.ZERO)

    /** Is the bridge fully backed? */
    val isFullyBacked: Boolean
        get() = backingRatio >= BigDecimal(100)
}
