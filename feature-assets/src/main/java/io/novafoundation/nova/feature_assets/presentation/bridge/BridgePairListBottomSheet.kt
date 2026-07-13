package io.novafoundation.nova.feature_assets.presentation.bridge

import android.content.Context
import android.os.Bundle
import androidx.recyclerview.widget.DiffUtil
import coil.ImageLoader
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.images.Icon
import io.novafoundation.nova.common.utils.images.setIconOrMakeGone
import io.novafoundation.nova.common.utils.inflater
import io.novafoundation.nova.common.view.bottomSheet.list.dynamic.DynamicListBottomSheet
import io.novafoundation.nova.common.view.bottomSheet.list.dynamic.DynamicListSheetAdapter
import io.novafoundation.nova.common.view.bottomSheet.list.dynamic.HolderCreator
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.databinding.ItemBridgePairListBinding

data class BridgePairUi(
    val pair: BridgePair,
    val icon: Icon,
    val title: String
)

class BridgePairListBottomSheet(
    context: Context,
    data: List<BridgePairUi>,
    onClicked: (BridgePairUi) -> Unit
) : DynamicListBottomSheet<BridgePairUi>(
    context,
    Payload(data),
    BridgePairDiffCallback,
    onClicked = { _, item -> onClicked(item) }
) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.bridge_title)
    }

    override fun holderCreator(): HolderCreator<BridgePairUi> = {
        BridgePairListHolder(ItemBridgePairListBinding.inflate(it.inflater(), it, false))
    }
}

class BridgePairListHolder(
    private val binder: ItemBridgePairListBinding
) : DynamicListSheetAdapter.Holder<BridgePairUi>(binder.root) {

    private val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.NONE) {
        FeatureUtils.getCommonApi(binder.root.context).imageLoader()
    }

    override fun bind(item: BridgePairUi, isSelected: Boolean, handler: DynamicListSheetAdapter.Handler<BridgePairUi>) {
        binder.itemBridgePairIcon.setIconOrMakeGone(item.icon, imageLoader)
        binder.itemBridgePairTitle.text = item.title
        binder.root.setOnClickListener { handler.itemClicked(item) }
    }
}

private object BridgePairDiffCallback : DiffUtil.ItemCallback<BridgePairUi>() {
    override fun areItemsTheSame(oldItem: BridgePairUi, newItem: BridgePairUi): Boolean {
        return oldItem.pair == newItem.pair
    }

    override fun areContentsTheSame(oldItem: BridgePairUi, newItem: BridgePairUi): Boolean {
        return true
    }
}
