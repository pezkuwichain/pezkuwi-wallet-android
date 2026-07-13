package io.novafoundation.nova.feature_assets.presentation.balance.list.view

import android.content.res.ColorStateList
import android.graphics.Color
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
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
import io.novafoundation.nova.feature_assets.presentation.citizenship.CitizenshipStatus

class PezkuwiDashboardAdapter(
    private val handler: Handler
) : SingleItemAdapter<PezkuwiDashboardHolder>(isShownByDefault = false) {

    interface Handler {
        fun onBasvuruClicked()
        fun onSignClicked()
        fun onShareReferralClicked()
        fun onStartTrackingClicked()
    }

    private var model: PezkuwiDashboardModel? = null
    private var trackingLoading: Boolean = false

    // Survives ViewHolder recycling (scroll) within the process, but not process restart —
    // resets to collapsed (false) whenever the app is freshly opened, by design.
    private var isExpanded: Boolean = false

    fun setModel(model: PezkuwiDashboardModel) {
        this.model = model
        notifyChangedIfShown()
    }

    fun setTrackingLoading(loading: Boolean) {
        this.trackingLoading = loading
        notifyChangedIfShown()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PezkuwiDashboardHolder {
        val binding = ItemPezkuwiDashboardBinding.inflate(parent.inflater(), parent, false)
        return PezkuwiDashboardHolder(binding, handler) { expanded -> isExpanded = expanded }
    }

    override fun onBindViewHolder(holder: PezkuwiDashboardHolder, position: Int) {
        model?.let { holder.bind(it, trackingLoading, isExpanded) }
    }

    override fun getItemViewType(position: Int): Int {
        return PezkuwiDashboardHolder.viewType
    }
}

class PezkuwiDashboardHolder(
    private val binder: ItemPezkuwiDashboardBinding,
    handler: PezkuwiDashboardAdapter.Handler,
    private val onExpandedChanged: (Boolean) -> Unit
) : RecyclerView.ViewHolder(binder.root) {

    companion object : WithViewType {
        override val viewType: Int = R.layout.item_pezkuwi_dashboard
    }

    init {
        binder.pezkuwiDashboardBasvuruButton.setOnClickListener { handler.onBasvuruClicked() }
        binder.pezkuwiDashboardSignButton.setOnClickListener { handler.onSignClicked() }
        binder.pezkuwiDashboardShareButton.setOnClickListener { handler.onShareReferralClicked() }
        binder.pezkuwiDashboardStartTrackingButton.setOnClickListener { handler.onStartTrackingClicked() }

        binder.pezkuwiDashboardCollapsedBar.setOnClickListener { setExpanded(true) }
        binder.pezkuwiDashboardCollapseButton.setOnClickListener { setExpanded(false) }
    }

    private fun setExpanded(expanded: Boolean) {
        TransitionManager.beginDelayedTransition(binder.pezkuwiDashboardRoot, AutoTransition().apply { duration = 200 })
        binder.pezkuwiDashboardCollapsedBar.visibility = if (expanded) View.GONE else View.VISIBLE
        binder.pezkuwiDashboardExpandedContent.visibility = if (expanded) View.VISIBLE else View.GONE
        onExpandedChanged(expanded)
    }

    fun bind(model: PezkuwiDashboardModel, trackingLoading: Boolean = false, isExpanded: Boolean = false) {
        bindRoles(model.roles)
        binder.pezkuwiDashboardTrustValue.text = model.trustScore
        binder.pezkuwiDashboardTrustValueCollapsed.text = model.trustScore
        binder.pezkuwiDashboardWelatiCount.text = model.welatiCount
        bindButtons(model.citizenshipStatus)

        // Reflect current expand state without animating (this runs on every bind/rebind,
        // e.g. after RecyclerView recycling — animation is only for user-initiated toggles).
        binder.pezkuwiDashboardCollapsedBar.visibility = if (isExpanded) View.GONE else View.VISIBLE
        binder.pezkuwiDashboardExpandedContent.visibility = if (isExpanded) View.VISIBLE else View.GONE

        val showTracking = !model.isTrackingScore && model.citizenshipStatus == CitizenshipStatus.APPROVED
        binder.pezkuwiDashboardStartTrackingButton.visibility = if (showTracking) View.VISIBLE else View.GONE

        if (showTracking) {
            binder.pezkuwiDashboardStartTrackingButton.isEnabled = !trackingLoading
            binder.pezkuwiDashboardStartTrackingButton.text = if (trackingLoading) {
                "..."
            } else {
                binder.root.context.getString(R.string.pezkuwi_dashboard_start_tracking)
            }
        }
    }

    private fun bindButtons(status: CitizenshipStatus) {
        if (status == CitizenshipStatus.APPROVED) {
            // Citizen: "Onayla" (approve referrals) + Share
            binder.pezkuwiDashboardBasvuruButton.visibility = View.VISIBLE
            binder.pezkuwiDashboardBasvuruButton.setText(R.string.citizenship_approve_button)
            binder.pezkuwiDashboardSignButton.visibility = View.GONE
            binder.pezkuwiDashboardShareButton.visibility = View.VISIBLE
            binder.pezkuwiDashboardShareButton.isEnabled = true
            binder.pezkuwiDashboardShareButton.alpha = 1f
        } else {
            // Not yet citizen: show all 3
            binder.pezkuwiDashboardBasvuruButton.visibility = View.VISIBLE
            binder.pezkuwiDashboardBasvuruButton.setText(R.string.pezkuwi_dashboard_basvuru)
            binder.pezkuwiDashboardSignButton.visibility = View.VISIBLE
            binder.pezkuwiDashboardShareButton.visibility = View.VISIBLE

            val signEnabled = status == CitizenshipStatus.REFERRER_APPROVED
            binder.pezkuwiDashboardSignButton.isEnabled = signEnabled
            binder.pezkuwiDashboardSignButton.alpha = if (signEnabled) 1f else 0.4f

            binder.pezkuwiDashboardShareButton.isEnabled = false
            binder.pezkuwiDashboardShareButton.alpha = 0.4f
        }
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
