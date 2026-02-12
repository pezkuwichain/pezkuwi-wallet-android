package io.novafoundation.nova.feature_assets.presentation.bridge

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import io.novafoundation.nova.common.base.BaseFragment
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.view.setState
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.databinding.FragmentBridgeBinding
import io.novafoundation.nova.feature_assets.di.AssetsFeatureApi
import io.novafoundation.nova.feature_assets.di.AssetsFeatureComponent

class BridgeFragment : BaseFragment<BridgeViewModel, FragmentBridgeBinding>() {

    override fun createBinding() = FragmentBridgeBinding.inflate(layoutInflater)

    override fun initViews() {
        binder.bridgeToolbar.setHomeButtonListener { viewModel.backClicked() }

        // Direction toggle
        binder.bridgeDirectionDotToHez.setOnClickListener {
            viewModel.setDirection(BridgeDirection.DOT_TO_HEZ)
        }

        binder.bridgeDirectionHezToDot.setOnClickListener {
            viewModel.setDirection(BridgeDirection.HEZ_TO_DOT)
        }

        // Amount input
        binder.bridgeFromAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val amount = s?.toString()?.toDoubleOrNull() ?: 0.0
                viewModel.setAmount(amount)
            }
        })

        // Swap button
        binder.bridgeSwapButton.setOnClickListener {
            viewModel.swapClicked()
        }
    }

    override fun inject() {
        FeatureUtils.getFeature<AssetsFeatureComponent>(
            requireContext(),
            AssetsFeatureApi::class.java
        )
            .bridgeComponentFactory()
            .create(this)
            .inject(this)
    }

    override fun subscribe(viewModel: BridgeViewModel) {
        viewModel.direction.observe { direction ->
            updateDirectionUI(direction)
        }

        viewModel.outputAmount.observe { output ->
            binder.bridgeToAmount.text = output
        }

        viewModel.exchangeRateText.observe { rate ->
            binder.bridgeRate.text = rate
        }

        viewModel.minimumText.observe { minimum ->
            binder.bridgeMinimum.text = minimum
        }

        viewModel.buttonState.observe { state ->
            binder.bridgeSwapButton.setState(state)
        }

        viewModel.showHezToDotWarning.observe { show ->
            binder.bridgeHezToDotWarning.visibility = if (show) View.VISIBLE else View.GONE
        }

        viewModel.hezToDotBlocked.observe { blocked ->
            if (blocked) {
                binder.bridgeHezToDotWarning.setBackgroundColor(resources.getColor(R.color.error_block_background, null))
            } else {
                binder.bridgeHezToDotWarning.setBackgroundColor(resources.getColor(R.color.warning_block_background, null))
            }
        }

        viewModel.blockReason.observe { reason ->
            if (reason.isNotEmpty()) {
                binder.bridgeHezToDotWarning.text = reason
            } else {
                binder.bridgeHezToDotWarning.text = getString(R.string.bridge_hez_to_dot_warning)
            }
        }
    }

    private fun updateDirectionUI(direction: BridgeDirection) {
        when (direction) {
            BridgeDirection.DOT_TO_HEZ -> {
                binder.bridgeDirectionDotToHez.setBackgroundResource(R.drawable.bg_button_primary)
                binder.bridgeDirectionDotToHez.setTextColor(resources.getColor(R.color.text_primary, null))
                binder.bridgeDirectionHezToDot.background = null
                binder.bridgeDirectionHezToDot.setTextColor(resources.getColor(R.color.text_secondary, null))

                binder.bridgeFromToken.text = "DOT"
                binder.bridgeToToken.text = "HEZ"
            }
            BridgeDirection.HEZ_TO_DOT -> {
                binder.bridgeDirectionHezToDot.setBackgroundResource(R.drawable.bg_button_primary)
                binder.bridgeDirectionHezToDot.setTextColor(resources.getColor(R.color.text_primary, null))
                binder.bridgeDirectionDotToHez.background = null
                binder.bridgeDirectionDotToHez.setTextColor(resources.getColor(R.color.text_secondary, null))

                binder.bridgeFromToken.text = "HEZ"
                binder.bridgeToToken.text = "DOT"
            }
        }
    }
}

enum class BridgeDirection {
    DOT_TO_HEZ,
    HEZ_TO_DOT
}
