package io.novafoundation.nova.feature_dapp_impl.presentation.browser.main.kurd

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import io.novafoundation.nova.common.utils.WithContextExtensions
import io.novafoundation.nova.common.utils.setImageTintRes
import io.novafoundation.nova.common.utils.setTextColorRes
import io.novafoundation.nova.common.view.bottomSheet.BaseBottomSheet
import io.novafoundation.nova.feature_dapp_impl.R
import io.novafoundation.nova.feature_dapp_impl.databinding.SheetKurdDetailsBinding

/** What was verified about the `.kurd` page the tab shows, from its address bar badge. */
class KurdDetailsSheet(
    context: Context,
    private val page: KurdPageUi.Open,
    private val ownerText: String,
    private val onClearSiteData: () -> Unit,
) : BaseBottomSheet<SheetKurdDetailsBinding>(context, R.style.BottomSheetDialog), WithContextExtensions by WithContextExtensions(context) {

    override val binder: SheetKurdDetailsBinding = SheetKurdDetailsBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val details = page.details
        binder.kurdDetailsName.text = page.name.name

        when (details.badge) {
            KurdPageUi.Badge.OFFICIAL -> {
                binder.kurdDetailsIcon.setImageResource(R.drawable.shield_checkmark_outline)
                binder.kurdDetailsIcon.setImageTintRes(R.color.icon_positive)
                binder.kurdDetailsKind.setText(R.string.dapp_kurd_kind_official)
                binder.kurdDetailsKind.setTextColorRes(R.color.text_positive)
                binder.kurdDetailsExplanation.setText(R.string.dapp_kurd_official_explanation)
            }
            KurdPageUi.Badge.REGISTERED -> {
                binder.kurdDetailsIcon.setImageResource(R.drawable.ic_lock)
                binder.kurdDetailsIcon.setImageTintRes(R.color.icon_secondary)
                binder.kurdDetailsKind.setText(R.string.dapp_kurd_kind_registered)
                binder.kurdDetailsKind.setTextColorRes(R.color.text_secondary)
                binder.kurdDetailsExplanation.setText(R.string.dapp_kurd_registered_explanation)
            }
            KurdPageUi.Badge.UNVERIFIED -> {
                binder.kurdDetailsIcon.setImageResource(R.drawable.ic_warning_filled)
                binder.kurdDetailsIcon.setImageTintRes(R.color.icon_warning)
                binder.kurdDetailsKind.setText(R.string.dapp_kurd_kind_unverified)
                binder.kurdDetailsKind.setTextColorRes(R.color.text_warning)
                binder.kurdDetailsExplanation.setText(R.string.dapp_kurd_unverified_explanation)
            }
        }

        binder.kurdDetailsVerification.setTitle(R.string.dapp_kurd_details_verification)
        binder.kurdDetailsVerification.showValue(verificationText(details))
        binder.kurdDetailsOwner.setTitle(R.string.dapp_kurd_details_owner)
        binder.kurdDetailsOwner.showValue(ownerText)
        binder.kurdDetailsContent.setTitle(R.string.dapp_kurd_details_content)
        binder.kurdDetailsContent.showValue(shortened(details.contentId))

        binder.kurdDetailsClearData.setOnClickListener {
            onClearSiteData()
            dismiss()
        }
    }

    private fun verificationText(details: KurdPageUi.Details): String = when {
        details.badge == KurdPageUi.Badge.UNVERIFIED -> context.getString(R.string.dapp_kurd_verified_by_nodes)
        details.provenAtBlock != null -> context.getString(R.string.dapp_kurd_verified_at_block, details.provenAtBlock.toString())
        else -> context.getString(R.string.dapp_kurd_verified_on_chain)
    }

    private fun shortened(cid: String) = if (cid.length > 12) cid.take(4) + "…" + cid.takeLast(4) else cid
}
