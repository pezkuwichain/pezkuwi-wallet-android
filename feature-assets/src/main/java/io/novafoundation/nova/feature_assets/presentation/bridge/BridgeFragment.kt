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

        // Pair selector
        binder.bridgePairDotHez.setOnClickListener {
            viewModel.setPair(BridgePair.DOT_HEZ)
        }

        binder.bridgePairUsdt.setOnClickListener {
            viewModel.setPair(BridgePair.USDT)
        }

        // Direction toggle
        binder.bridgeDirectionLeft.setOnClickListener {
            viewModel.setDirectionLeft()
        }

        binder.bridgeDirectionRight.setOnClickListener {
            viewModel.setDirectionRight()
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
        viewModel.pair.observe { pair ->
            updatePairUI(pair)
        }

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

        viewModel.showWarning.observe { show ->
            binder.bridgeHezToDotWarning.visibility = if (show) View.VISIBLE else View.GONE
        }

        viewModel.warningBlocked.observe { blocked ->
            if (blocked) {
                binder.bridgeHezToDotWarning.setBackgroundColor(resources.getColor(R.color.error_block_background, null))
            } else {
                binder.bridgeHezToDotWarning.setBackgroundColor(resources.getColor(R.color.warning_block_background, null))
            }
        }

        viewModel.warningText.observe { text ->
            if (text.isNotEmpty()) {
                binder.bridgeHezToDotWarning.text = text
            }
        }
    }

    private fun updatePairUI(pair: BridgePair) {
        when (pair) {
            BridgePair.DOT_HEZ -> {
                binder.bridgePairDotHez.setBackgroundResource(R.drawable.bg_button_primary)
                binder.bridgePairDotHez.setTextColor(resources.getColor(R.color.text_primary, null))
                binder.bridgePairUsdt.background = null
                binder.bridgePairUsdt.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
            BridgePair.USDT -> {
                binder.bridgePairUsdt.setBackgroundResource(R.drawable.bg_button_primary)
                binder.bridgePairUsdt.setTextColor(resources.getColor(R.color.text_primary, null))
                binder.bridgePairDotHez.background = null
                binder.bridgePairDotHez.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
        }
    }

    private fun updateDirectionUI(direction: BridgeDirection) {
        val isLeft = direction == BridgeDirection.DOT_TO_HEZ || direction == BridgeDirection.USDT_TO_WUSDT

        if (isLeft) {
            binder.bridgeDirectionLeft.setBackgroundResource(R.drawable.bg_button_primary)
            binder.bridgeDirectionLeft.setTextColor(resources.getColor(R.color.text_primary, null))
            binder.bridgeDirectionRight.background = null
            binder.bridgeDirectionRight.setTextColor(resources.getColor(R.color.text_secondary, null))
        } else {
            binder.bridgeDirectionRight.setBackgroundResource(R.drawable.bg_button_primary)
            binder.bridgeDirectionRight.setTextColor(resources.getColor(R.color.text_primary, null))
            binder.bridgeDirectionLeft.background = null
            binder.bridgeDirectionLeft.setTextColor(resources.getColor(R.color.text_secondary, null))
        }

        when (direction) {
            BridgeDirection.DOT_TO_HEZ -> {
                binder.bridgeDirectionLeft.text = "DOT → HEZ"
                binder.bridgeDirectionRight.text = "HEZ → DOT"
                binder.bridgeFromToken.text = "DOT"
                binder.bridgeToToken.text = "HEZ"
            }
            BridgeDirection.HEZ_TO_DOT -> {
                binder.bridgeDirectionLeft.text = "DOT → HEZ"
                binder.bridgeDirectionRight.text = "HEZ → DOT"
                binder.bridgeFromToken.text = "HEZ"
                binder.bridgeToToken.text = "DOT"
            }
            BridgeDirection.USDT_TO_WUSDT -> {
                binder.bridgeDirectionLeft.text = "USDT(Pol) → USDT(Pez)"
                binder.bridgeDirectionRight.text = "USDT(Pez) → USDT(Pol)"
                binder.bridgeFromToken.text = "USDT"
                binder.bridgeToToken.text = "USDT"
            }
            BridgeDirection.WUSDT_TO_USDT -> {
                binder.bridgeDirectionLeft.text = "USDT(Pol) → USDT(Pez)"
                binder.bridgeDirectionRight.text = "USDT(Pez) → USDT(Pol)"
                binder.bridgeFromToken.text = "USDT"
                binder.bridgeToToken.text = "USDT"
            }
        }
    }
}

enum class BridgePair {
    DOT_HEZ,
    USDT
}

enum class BridgeDirection {
    DOT_TO_HEZ,
    HEZ_TO_DOT,
    USDT_TO_WUSDT,
    WUSDT_TO_USDT
}
