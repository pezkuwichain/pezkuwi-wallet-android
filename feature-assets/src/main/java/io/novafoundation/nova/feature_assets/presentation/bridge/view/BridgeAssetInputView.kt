package io.novafoundation.nova.feature_assets.presentation.bridge.view

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import coil.ImageLoader
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.WithContextExtensions
import io.novafoundation.nova.common.utils.images.asUrlIcon
import io.novafoundation.nova.common.utils.images.setIconOrMakeGone
import io.novafoundation.nova.common.utils.inflater
import io.novafoundation.nova.common.view.shape.getInputBackground
import io.novafoundation.nova.feature_account_api.presenatation.chain.setTokenIcon
import io.novafoundation.nova.feature_assets.databinding.ViewBridgeAssetInputBinding
import io.novafoundation.nova.feature_assets.presentation.bridge.BridgeAssetCardUi

class BridgeAssetInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr),
    WithContextExtensions by WithContextExtensions(context) {

    private val binder = ViewBridgeAssetInputBinding.inflate(inflater(), this)

    val amountInput: EditText
        get() = binder.bridgeAssetInputField

    private val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.NONE) {
        FeatureUtils.getCommonApi(context).imageLoader()
    }

    init {
        binder.bridgeAssetInputContainer.background = context.getInputBackground()
    }

    fun setCardClickListener(listener: OnClickListener) {
        binder.bridgeAssetInputContainer.setOnClickListener(listener)
        binder.bridgeAssetInputChevron.isVisible = true
    }

    fun setEditable(editable: Boolean) {
        amountInput.isFocusable = editable
        amountInput.isFocusableInTouchMode = editable
        amountInput.isClickable = editable
        amountInput.isCursorVisible = editable
    }

    fun setAmountText(text: CharSequence) {
        if (amountInput.text?.toString() != text.toString()) {
            amountInput.setText(text)
        }
    }

    fun setModel(model: BridgeAssetCardUi) {
        binder.bridgeAssetInputImage.setTokenIcon(model.assetIcon, imageLoader)
        binder.bridgeAssetInputToken.text = model.symbol
        binder.bridgeAssetInputSubtitle.text = model.chainName
        binder.bridgeAssetInputSubtitleImage.setIconOrMakeGone(model.chainIconUrl?.asUrlIcon(), imageLoader)
    }
}
