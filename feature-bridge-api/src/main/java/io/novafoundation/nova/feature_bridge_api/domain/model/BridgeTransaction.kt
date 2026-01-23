package io.novafoundation.nova.feature_bridge_api.domain.model

import java.math.BigDecimal

enum class BridgeTransactionType {
    DEPOSIT,    // Polkadot USDT -> Pezkuwi wUSDT
    WITHDRAW    // Pezkuwi wUSDT -> Polkadot USDT
}

enum class BridgeTransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

/**
 * A bridge transaction (deposit or withdrawal)
 */
data class BridgeTransaction(
    val id: String,
    val type: BridgeTransactionType,
    val status: BridgeTransactionStatus,
    val amount: BigDecimal,
    val fee: BigDecimal,
    val netAmount: BigDecimal,
    val sourceAddress: String,
    val destinationAddress: String,
    val sourceTxHash: String?,
    val destinationTxHash: String?,
    val createdAt: Long,
    val completedAt: Long?
)
