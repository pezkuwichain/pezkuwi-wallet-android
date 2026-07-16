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
    /** Computed on the input screen (BridgeViewModel.exceedsAutoPayBounds) from the real on-chain
     *  automation-key allowance and the hard per-tx cap, at the moment the user confirmed - tells
     *  this screen whether to expect a fast auto-pay or manual 3-of-5 review from frame one,
     *  instead of only finding out after a balance-watch timeout elapses. */
    val expectManualReview: Boolean,
) : Parcelable
