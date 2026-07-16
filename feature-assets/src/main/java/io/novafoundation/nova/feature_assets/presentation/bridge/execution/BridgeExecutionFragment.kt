package io.novafoundation.nova.feature_assets.presentation.bridge.execution

import android.os.Bundle
import android.view.View
import io.novafoundation.nova.common.base.BaseFragment
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.view.AlertView
import io.novafoundation.nova.common.view.setState
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.databinding.FragmentBridgeExecutionBinding
import io.novafoundation.nova.feature_assets.di.AssetsFeatureApi
import io.novafoundation.nova.feature_assets.di.AssetsFeatureComponent

private const val KEY_PAYLOAD = "KEY_PAYLOAD"

class BridgeExecutionFragment : BaseFragment<BridgeExecutionViewModel, FragmentBridgeExecutionBinding>() {

    companion object {

        fun getBundle(payload: BridgeExecutionPayload) = Bundle().apply {
            putParcelable(KEY_PAYLOAD, payload)
        }
    }

    override fun createBinding() = FragmentBridgeExecutionBinding.inflate(layoutInflater)

    override fun initViews() {
        binder.bridgeExecutionToolbar.setHomeButtonListener { viewModel.doneClicked() }

        binder.bridgeExecutionDoneButton.setOnClickListener { viewModel.doneClicked() }

        binder.bridgeExecutionFromCard.setEditable(false)
        binder.bridgeExecutionToCard.setEditable(false)
    }

    override fun inject() {
        val payload = argument<BridgeExecutionPayload>(KEY_PAYLOAD)

        FeatureUtils.getFeature<AssetsFeatureComponent>(
            requireContext(),
            AssetsFeatureApi::class.java
        )
            .bridgeExecutionComponentFactory()
            .create(this, payload)
            .inject(this)
    }

    override fun subscribe(viewModel: BridgeExecutionViewModel) {
        viewModel.label.observe { text ->
            binder.bridgeExecutionLabel.text = text
        }

        viewModel.timerState.observe { state ->
            if (state == null) {
                binder.bridgeExecutionTimer.visibility = View.GONE
            } else {
                binder.bridgeExecutionTimer.visibility = View.VISIBLE
                binder.bridgeExecutionTimer.setState(state)
            }
        }

        viewModel.doneButtonVisible.observe { visible ->
            binder.bridgeExecutionDoneButton.visibility = if (visible) View.VISIBLE else View.GONE
            // "Do not close the app!" only makes sense while something is genuinely still in
            // flight - doneButtonVisible becomes true exactly once the operation has resolved
            // (success, pending review, or failure), so its inverse is the same signal without a
            // separate LiveData to keep in sync.
            binder.bridgeExecutionDoNotClose.visibility = if (visible) View.GONE else View.VISIBLE
        }

        viewModel.fromCard.observe { model ->
            binder.bridgeExecutionFromCard.setModel(model)
        }

        viewModel.toCard.observe { model ->
            binder.bridgeExecutionToCard.setModel(model)
        }

        viewModel.fromAmountText.observe { text ->
            binder.bridgeExecutionFromCard.setAmountText(text)
        }

        viewModel.toAmountText.observe { text ->
            binder.bridgeExecutionToCard.setAmountText(text)
        }

        // Single source of truth for the alert banner - only OriginFailed (error) and
        // DestinationPendingReview (warning) show it, everything else keeps it hidden. Avoids the
        // old screen's problem of several independent LiveData all touching the same view.
        viewModel.state.observe { state ->
            when (state) {
                is BridgeExecutionState.OriginFailed -> {
                    binder.bridgeExecutionPendingReviewAlert.visibility = View.VISIBLE
                    binder.bridgeExecutionPendingReviewAlert.setStylePreset(AlertView.StylePreset.ERROR)
                    binder.bridgeExecutionPendingReviewAlert.setMessage(state.message)
                }
                BridgeExecutionState.DestinationPendingReview -> {
                    binder.bridgeExecutionPendingReviewAlert.visibility = View.VISIBLE
                    binder.bridgeExecutionPendingReviewAlert.setStylePreset(AlertView.StylePreset.WARNING)
                    binder.bridgeExecutionPendingReviewAlert.setMessage(getString(R.string.bridge_deposit_pending_message))
                }
                else -> {
                    binder.bridgeExecutionPendingReviewAlert.visibility = View.GONE
                }
            }
        }
    }
}
