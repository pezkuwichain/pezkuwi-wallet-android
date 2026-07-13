package io.novafoundation.nova.feature_assets.presentation.bridge

import android.text.Editable
import android.text.TextWatcher
import io.novafoundation.nova.common.base.BaseFragment
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.setVisible
import io.novafoundation.nova.common.view.AlertView
import io.novafoundation.nova.common.view.setState
import io.novafoundation.nova.feature_assets.databinding.FragmentBridgeBinding
import io.novafoundation.nova.feature_assets.di.AssetsFeatureApi
import io.novafoundation.nova.feature_assets.di.AssetsFeatureComponent

class BridgeFragment : BaseFragment<BridgeViewModel, FragmentBridgeBinding>() {

    override fun createBinding() = FragmentBridgeBinding.inflate(layoutInflater)

    override fun initViews() {
        binder.bridgeToolbar.setHomeButtonListener { viewModel.backClicked() }

        binder.bridgeToCard.setEditable(false)

        // Tapping the "from" card opens the pair picker - replaces the old segmented pair buttons
        binder.bridgeFromCard.setCardClickListener {
            val options = viewModel.pairOptions.value.orEmpty()
            if (options.isNotEmpty()) {
                BridgePairListBottomSheet(requireContext(), options) { selected ->
                    viewModel.setPair(selected.pair)
                }.show()
            }
        }

        // One-tap direction flip - replaces the old segmented direction buttons
        binder.bridgeFlipButton.setOnClickListener {
            when (viewModel.direction.value) {
                BridgeDirection.DOT_TO_HEZ, BridgeDirection.USDT_TO_WUSDT -> viewModel.setDirectionRight()
                BridgeDirection.HEZ_TO_DOT, BridgeDirection.WUSDT_TO_USDT -> viewModel.setDirectionLeft()
                null -> Unit
            }
        }

        // Amount input
        binder.bridgeFromCard.amountInput.addTextChangedListener(object : TextWatcher {
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
        viewModel.fromCard.observe { model ->
            binder.bridgeFromCard.setModel(model)
        }

        viewModel.toCard.observe { model ->
            binder.bridgeToCard.setModel(model)
        }

        viewModel.outputAmount.observe { output ->
            binder.bridgeToCard.setAmountText(output)
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
            binder.bridgeWarningAlert.setVisible(show)
        }

        viewModel.warningBlocked.observe { blocked ->
            binder.bridgeWarningAlert.setStylePreset(
                if (blocked) AlertView.StylePreset.ERROR else AlertView.StylePreset.WARNING
            )
        }

        viewModel.warningText.observe { text ->
            if (text.isNotEmpty()) {
                binder.bridgeWarningAlert.setMessage(text)
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
