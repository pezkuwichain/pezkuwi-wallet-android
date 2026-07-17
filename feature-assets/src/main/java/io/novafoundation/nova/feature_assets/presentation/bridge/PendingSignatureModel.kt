package io.novafoundation.nova.feature_assets.presentation.bridge

import io.novafoundation.nova.feature_account_api.presenatation.chain.ChainUi
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.PendingBridgeApproval

data class PendingSignatureModel(
    val approval: PendingBridgeApproval,
    val chain: ChainUi,
    val amountText: String,
    val destinationText: String,
    val progress: String,
)
