package io.novafoundation.nova.feature_assets.presentation.bridge.execution

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Everything BridgeExecutionViewModel needs to submit the origin transfer and watch for the
 * destination credit - deliberately just ids/amounts (not display strings), matching this
 * codebase's AssetPayload convention: the execution screen re-derives chain/asset display data
 * itself from ChainRegistry rather than trusting stale strings carried across navigation.
 */
@Parcelize
class BridgeExecutionPayload(
    val originChainId: String,
    val originAssetId: Int,
    val bridgeAddress: String,
    val amount: Double,
    val destChainId: String,
    val destAssetId: Int,
) : Parcelable
