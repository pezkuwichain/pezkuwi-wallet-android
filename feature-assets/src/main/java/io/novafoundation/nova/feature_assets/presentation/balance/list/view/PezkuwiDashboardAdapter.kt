package io.novafoundation.nova.feature_assets.presentation.balance.list.view

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import io.novafoundation.nova.common.list.SingleItemAdapter
import io.novafoundation.nova.common.utils.dp
import io.novafoundation.nova.common.utils.inflater
import io.novafoundation.nova.common.utils.recyclerView.WithViewType
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.databinding.ItemPezkuwiDashboardBinding
import io.novafoundation.nova.feature_assets.presentation.balance.list.model.PezkuwiDashboardModel

class PezkuwiDashboardAdapter(
    private val handler: Handler
) : SingleItemAdapter<PezkuwiDashboardHolder>(isShownByDefault = false) {

    interface Handler {
        fun onBasvuruClicked()
    }

    private var model: PezkuwiDashboardModel? = null

    fun setModel(model: PezkuwiDashboardModel) {
        this.model = model
        notifyChangedIfShown()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PezkuwiDashboardHolder {
        val binding = ItemPezkuwiDashboardBinding.inflate(parent.inflater(), parent, false)
        return PezkuwiDashboardHolder(binding, handler)
    }

    override fun onBindViewHolder(holder: PezkuwiDashboardHolder, position: Int) {
        model?.let { holder.bind(it) }
    }

    override fun getItemViewType(position: Int): Int {
        return PezkuwiDashboardHolder.viewType
    }
}

class PezkuwiDashboardHolder(
    private val binder: ItemPezkuwiDashboardBinding,
    handler: PezkuwiDashboardAdapter.Handler
) : RecyclerView.ViewHolder(binder.root) {

    companion object : WithViewType {
        override val viewType: Int = R.layout.item_pezkuwi_dashboard
    }

    init {
        binder.pezkuwiDashboardBasvuruButton.setOnClickListener { handler.onBasvuruClicked() }
    }

    fun bind(model: PezkuwiDashboardModel) {
        bindRoles(model.roles)
        binder.pezkuwiDashboardTrustValue.text = model.trustScore
        binder.pezkuwiDashboardWelatiCount.text = model.welatiCount
    }

    private fun bindRoles(roles: List<String>) {
        val flexbox = binder.pezkuwiDashboardRoles
        flexbox.removeAllViews()

        roles.forEach { role ->
            val chip = Chip(flexbox.context).apply {
                text = role
                isClickable = false
                isCheckable = false
                setTextColor(Color.WHITE)
                chipBackgroundColor = ColorStateList.valueOf(0x33FFFFFF)
                chipStrokeWidth = 0f
                val params = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 8.dp(context), 4.dp(context))
                layoutParams = params
            }
            flexbox.addView(chip)
        }
    }
}
