package io.novafoundation.nova.feature_dapp_impl.presentation.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import io.novafoundation.nova.common.utils.WithContextExtensions
import io.novafoundation.nova.common.view.bottomSheet.BaseBottomSheet
import io.novafoundation.nova.feature_dapp_impl.R
import io.novafoundation.nova.feature_dapp_impl.databinding.SheetKurdLookalikeBinding
import io.novafoundation.nova.runtime.names.KurdName

class KurdLookalikeWarning(val typed: KurdName, val official: KurdName)

enum class KurdLookalikeChoice { GO_OFFICIAL, OPEN_TYPED }

/** Before opening a typed name that a proven official name could be mistaken for. */
class KurdLookalikeSheet(
    context: Context,
    private val warning: KurdLookalikeWarning,
    private val onChoice: (KurdLookalikeChoice) -> Unit,
    onCancel: () -> Unit,
) : BaseBottomSheet<SheetKurdLookalikeBinding>(context, R.style.BottomSheetDialog, onCancel), WithContextExtensions by WithContextExtensions(context) {

    override val binder: SheetKurdLookalikeBinding = SheetKurdLookalikeBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binder.kurdLookalikeTyped.setTitle(R.string.dapp_kurd_lookalike_typed)
        binder.kurdLookalikeTyped.showValue(warning.typed.name)
        binder.kurdLookalikeOfficial.setTitle(R.string.dapp_kurd_lookalike_official)
        binder.kurdLookalikeOfficial.showValue(warning.official.name, context.getString(R.string.dapp_kurd_badge_official))

        binder.kurdLookalikeGoOfficial.text = context.getString(R.string.dapp_kurd_lookalike_go, warning.official.name)
        binder.kurdLookalikeGoOfficial.setOnClickListener { choose(KurdLookalikeChoice.GO_OFFICIAL) }
        binder.kurdLookalikeOpenAnyway.text = context.getString(R.string.dapp_kurd_lookalike_open_anyway, warning.typed.name)
        binder.kurdLookalikeOpenAnyway.setOnClickListener { choose(KurdLookalikeChoice.OPEN_TYPED) }
    }

    private fun choose(choice: KurdLookalikeChoice) {
        onChoice(choice)
        dismiss()
    }
}
