package io.novafoundation.nova.feature_dapp_impl.presentation.browser.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import io.novafoundation.nova.common.utils.WithContextExtensions
import io.novafoundation.nova.common.utils.dp
import io.novafoundation.nova.common.utils.inflater
import io.novafoundation.nova.common.utils.makeVisible
import io.novafoundation.nova.common.utils.setImageTintRes
import io.novafoundation.nova.common.utils.setTextColorRes
import io.novafoundation.nova.common.utils.setVisible
import io.novafoundation.nova.feature_dapp_impl.R
import io.novafoundation.nova.feature_dapp_impl.databinding.ViewAddressBarBinding
import io.novafoundation.nova.feature_dapp_impl.presentation.browser.main.kurd.KurdPageUi

class AddressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), WithContextExtensions {

    private val binder = ViewAddressBarBinding.inflate(inflater(), this)

    override val providedContext: Context = context

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        background = addRipple(getRoundedCornerDrawable(R.color.dapp_blur_navigation_background, cornerSizeDp = 10), mask = getRippleMask(cornerSizeDp = 10))
    }

    fun setAddress(address: String) {
        binder.addressBarUrl.text = address
    }

    fun showSecureIcon(shouldShow: Boolean) {
        binder.addressBarIcon.setVisible(shouldShow)
    }

    /**
     * The badge of a `.kurd` page, or none. It replaces the padlock: a `.kurd` page is not reached
     * over the network the padlock speaks of, and the badge says what was verified instead.
     */
    fun showKurdBadge(badge: KurdPageUi.Badge?, onClick: () -> Unit) {
        val badgeView = binder.addressBarKurdBadge
        badgeView.setVisible(badge != null)
        binder.addressBarUrl.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> { marginEnd = (if (badge != null) 6 else 32).dp(context) }
        if (badge == null) {
            badgeView.setOnClickListener(null)
            return
        }

        val (textRes, colorRes, iconRes) = when (badge) {
            KurdPageUi.Badge.OFFICIAL -> Triple(R.string.dapp_kurd_badge_official, R.color.text_positive, R.drawable.shield_checkmark_outline)
            KurdPageUi.Badge.REGISTERED -> Triple(R.string.dapp_kurd_badge_registered, R.color.text_secondary, R.drawable.ic_lock)
            KurdPageUi.Badge.UNVERIFIED -> Triple(R.string.dapp_kurd_badge_unverified, R.color.text_warning, R.drawable.ic_warning_filled)
        }
        val iconTint = when (badge) {
            KurdPageUi.Badge.OFFICIAL -> R.color.icon_positive
            KurdPageUi.Badge.REGISTERED -> R.color.icon_secondary
            KurdPageUi.Badge.UNVERIFIED -> R.color.icon_warning
        }

        badgeView.setText(textRes)
        badgeView.setTextColorRes(colorRes)
        binder.addressBarIcon.makeVisible()
        binder.addressBarIcon.setImageResource(iconRes)
        binder.addressBarIcon.setImageTintRes(iconTint)
        binder.addressBarUrl.setTextColorRes(R.color.text_primary)
        badgeView.setOnClickListener { onClick() }
    }

    fun showSecure(shouldShow: Boolean) {
        binder.addressBarIcon.setImageResource(R.drawable.ic_lock)
        binder.addressBarIcon.setVisible(shouldShow)

        if (shouldShow) {
            binder.addressBarUrl.setTextColorRes(R.color.text_positive)
            binder.addressBarIcon.setImageTintRes(R.color.icon_positive)
        } else {
            binder.addressBarUrl.setTextColorRes(R.color.text_primary)
            binder.addressBarIcon.setImageTintRes(R.color.icon_primary)
        }
    }
}
