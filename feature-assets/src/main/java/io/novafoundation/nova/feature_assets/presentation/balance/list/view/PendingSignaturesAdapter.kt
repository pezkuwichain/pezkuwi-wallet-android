package io.novafoundation.nova.feature_assets.presentation.balance.list.view

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import io.novafoundation.nova.common.list.SingleItemAdapter
import io.novafoundation.nova.common.utils.inflater
import io.novafoundation.nova.common.utils.recyclerView.WithViewType
import io.novafoundation.nova.feature_account_api.presenatation.chain.loadChainIcon
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.databinding.ItemPendingSignatureRowBinding
import io.novafoundation.nova.feature_assets.databinding.ItemPendingSignaturesCardBinding
import io.novafoundation.nova.feature_assets.presentation.balance.list.model.PendingSignatureModel

class PendingSignaturesAdapter(
    private val imageLoader: ImageLoader,
    private val handler: Handler
) : SingleItemAdapter<PendingSignaturesHolder>(isShownByDefault = false) {

    interface Handler {
        fun onSignClicked(model: PendingSignatureModel)
    }

    private var models: List<PendingSignatureModel> = emptyList()

    fun setModels(models: List<PendingSignatureModel>) {
        this.models = models
        notifyChangedIfShown()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PendingSignaturesHolder {
        val binding = ItemPendingSignaturesCardBinding.inflate(parent.inflater(), parent, false)
        return PendingSignaturesHolder(binding, imageLoader, handler)
    }

    override fun onBindViewHolder(holder: PendingSignaturesHolder, position: Int) {
        holder.bind(models)
    }

    override fun getItemViewType(position: Int): Int {
        return PendingSignaturesHolder.viewType
    }
}

class PendingSignaturesHolder(
    private val binder: ItemPendingSignaturesCardBinding,
    private val imageLoader: ImageLoader,
    private val handler: PendingSignaturesAdapter.Handler
) : RecyclerView.ViewHolder(binder.root) {

    companion object : WithViewType {
        override val viewType: Int = R.layout.item_pending_signatures_card
    }

    fun bind(models: List<PendingSignatureModel>) {
        val container = binder.pendingSignaturesRowsContainer
        container.removeAllViews()

        models.forEach { model ->
            val rowBinding = ItemPendingSignatureRowBinding.inflate(container.inflater(), container, false)

            rowBinding.pendingSignatureChainIcon.loadChainIcon(model.chain.icon, imageLoader)
            // primaryValue (e.g. "100K USDT") is the amount - the whole point of this card - so it
            // takes the prominent title line; the call's own title/action name ("Transfer") isn't
            // shown at all here, only the destination (subtitle) is, to keep the row to two lines.
            rowBinding.pendingSignatureTitle.text = model.primaryValue ?: model.title
            rowBinding.pendingSignatureSubtitle.text = listOfNotNull(model.subtitle, model.chain.name, model.progress)
                .joinToString(separator = " • ")
            rowBinding.pendingSignatureSignButton.setOnClickListener { handler.onSignClicked(model) }

            container.addView(rowBinding.root)
        }
    }
}
