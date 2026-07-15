package io.novafoundation.nova.feature_assets.presentation.bridge.execution

/**
 * Every outcome the bridge execution screen can actually be in - the single source of truth the
 * UI renders from, instead of several independent booleans/LiveData (isExecuting, showWarning,
 * warningBlocked, depositWaitLabelVisible, ...) that each had to be kept in sync by hand on every
 * code path. A state that isn't listed here isn't a state this screen can reach.
 */
sealed class BridgeExecutionState {

    /** Submitting the real on-chain transfer on the origin chain and awaiting its own dispatch
     *  result - nothing has left the wallet's control yet as far as this screen can tell. */
    object SubmittingOrigin : BridgeExecutionState()

    /** The origin dispatch itself failed or was rejected (e.g. fee changed, node rejected it) -
     *  no funds moved. Safe to retry from scratch. */
    data class OriginFailed(val message: String) : BridgeExecutionState()

    /** Origin transfer confirmed on-chain - funds have left the wallet. Actively watching the
     *  destination balance for a real increase; the bridge's off-chain relay step has no
     *  callback this wallet can observe directly, so balance-delta is the most honest signal
     *  available. */
    object WaitingForDestination : BridgeExecutionState()

    /** Destination balance increase actually observed within the wait window. */
    object DestinationConfirmed : BridgeExecutionState()

    /** Not confirmed within the wait window - NOT a failure (the origin transfer already
     *  succeeded and is not reversible), just not observably complete yet. Could mean auto-pay
     *  is still in flight, or the amount exceeded the bridge's auto-pay bounds/reserve and is
     *  queued for manual 3-of-5 review, which can take longer than this screen waits. */
    object DestinationPendingReview : BridgeExecutionState()
}
