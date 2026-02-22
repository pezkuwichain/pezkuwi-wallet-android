package io.novafoundation.nova.feature_assets.presentation.citizenship

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import io.novafoundation.nova.common.base.BaseBottomSheetFragment
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.dp
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.data.repository.PendingApproval
import io.novafoundation.nova.feature_assets.databinding.FragmentCitizenshipBottomSheetBinding
import io.novafoundation.nova.feature_assets.di.AssetsFeatureApi
import io.novafoundation.nova.feature_assets.di.AssetsFeatureComponent

class CitizenshipBottomSheet : BaseBottomSheetFragment<CitizenshipViewModel, FragmentCitizenshipBottomSheetBinding>() {

    override fun createBinding() = FragmentCitizenshipBottomSheetBinding.inflate(layoutInflater)

    override fun inject() {
        FeatureUtils.getFeature<AssetsFeatureComponent>(
            requireContext(),
            AssetsFeatureApi::class.java
        )
            .citizenshipComponentFactory()
            .create(this)
            .inject(this)
    }

    override fun initViews() {
        setupRegionSpinner()

        binder.citizenshipActionButton.setOnClickListener {
            when (viewModel.citizenshipStatus.value) {
                CitizenshipStatus.NOT_STARTED -> submitForm()
                CitizenshipStatus.REFERRER_APPROVED -> viewModel.signApplication()
                else -> {}
            }
        }

        binder.citizenshipShareButton.setOnClickListener {
            viewModel.shareReferralLink()
        }
    }

    override fun subscribe(viewModel: CitizenshipViewModel) {
        viewModel.citizenshipStatus.observe(viewLifecycleOwner) { status ->
            updateUiForStatus(status)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binder.citizenshipProgress.visibility = if (loading) View.VISIBLE else View.GONE
            binder.citizenshipActionButton.isEnabled = !loading
        }

        viewModel.dismissEvent.observeEvent {
            dismiss()
        }

        viewModel.shareEvent.observeEvent { shareText ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(intent, null))
        }

        viewModel.pendingApprovals.observe(viewLifecycleOwner) { approvals ->
            bindInvitationsList(approvals)
        }
    }

    private fun setupRegionSpinner() {
        val regions = listOf(
            getString(R.string.citizenship_region_bakur),
            getString(R.string.citizenship_region_basur),
            getString(R.string.citizenship_region_rojava),
            getString(R.string.citizenship_region_rojhelat),
            getString(R.string.citizenship_region_kurdistan),
            getString(R.string.citizenship_region_diaspora)
        )

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, regions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binder.citizenshipRegionSpinner.adapter = adapter
    }

    private fun updateUiForStatus(status: CitizenshipStatus) {
        binder.citizenshipShareButton.visibility = View.GONE
        binder.citizenshipInvitationsHeader.visibility = View.GONE
        binder.citizenshipInvitationsScroll.visibility = View.GONE

        when (status) {
            CitizenshipStatus.LOADING -> {
                binder.citizenshipFormScroll.visibility = View.GONE
                binder.citizenshipStatusText.visibility = View.GONE
                binder.citizenshipActionButton.isEnabled = false
                binder.citizenshipProgress.visibility = View.VISIBLE
            }

            CitizenshipStatus.NOT_STARTED -> {
                binder.citizenshipFormScroll.visibility = View.VISIBLE
                binder.citizenshipStatusText.visibility = View.GONE
                binder.citizenshipActionButton.isEnabled = true
                binder.citizenshipActionButton.visibility = View.VISIBLE
                binder.citizenshipActionButton.text = getString(R.string.citizenship_apply)
                binder.citizenshipProgress.visibility = View.GONE
            }

            CitizenshipStatus.PENDING_REFERRAL -> {
                binder.citizenshipFormScroll.visibility = View.GONE
                binder.citizenshipStatusText.visibility = View.VISIBLE
                binder.citizenshipStatusText.text = getString(R.string.citizenship_pending)
                binder.citizenshipActionButton.isEnabled = false
                binder.citizenshipActionButton.visibility = View.VISIBLE
                binder.citizenshipActionButton.text = getString(R.string.citizenship_pending)
                binder.citizenshipProgress.visibility = View.GONE
            }

            CitizenshipStatus.REFERRER_APPROVED -> {
                binder.citizenshipFormScroll.visibility = View.GONE
                binder.citizenshipStatusText.visibility = View.VISIBLE
                binder.citizenshipStatusText.text = getString(R.string.citizenship_sign_description)
                binder.citizenshipActionButton.isEnabled = true
                binder.citizenshipActionButton.visibility = View.VISIBLE
                binder.citizenshipActionButton.text = getString(R.string.citizenship_sign)
                binder.citizenshipProgress.visibility = View.GONE
            }

            CitizenshipStatus.APPROVED -> {
                binder.citizenshipFormScroll.visibility = View.GONE
                binder.citizenshipStatusText.visibility = View.VISIBLE
                binder.citizenshipStatusText.text = getString(R.string.citizenship_approved)
                binder.citizenshipActionButton.visibility = View.GONE
                binder.citizenshipShareButton.visibility = View.VISIBLE
                binder.citizenshipInvitationsHeader.visibility = View.VISIBLE
                binder.citizenshipInvitationsScroll.visibility = View.VISIBLE
                binder.citizenshipProgress.visibility = View.GONE
            }
        }
    }

    private fun bindInvitationsList(approvals: List<PendingApproval>) {
        val container = binder.citizenshipInvitationsList
        container.removeAllViews()

        val header = binder.citizenshipInvitationsHeader
        val pendingCount = approvals.count { it.status == CitizenshipStatus.PENDING_REFERRAL }
        header.text = "My Invitations (${approvals.size})" +
            if (pendingCount > 0) " \u2022 $pendingCount pending" else ""

        if (approvals.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "No invitations yet"
                setTextColor(Color.parseColor("#78909C"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 8.dp(context), 0, 8.dp(context))
            }
            container.addView(emptyText)
            return
        }

        approvals.forEach { approval ->
            container.addView(createInvitationRow(approval))
        }
    }

    private fun createInvitationRow(approval: PendingApproval): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dp(context), 0, 6.dp(context))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Shortened address
        val addr = approval.applicantAddress
        val shortAddr = if (addr.length > 16) "${addr.take(8)}...${addr.takeLast(6)}" else addr

        val addressText = TextView(requireContext()).apply {
            text = shortAddr
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(addressText)

        when (approval.status) {
            CitizenshipStatus.PENDING_REFERRAL -> {
                val approveBtn = MaterialButton(requireContext()).apply {
                    text = getString(R.string.citizenship_approve_button)
                    textSize = 11f
                    isAllCaps = false
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                    setTextColor(Color.WHITE)
                    cornerRadius = 6.dp(context)
                    setPadding(12.dp(context), 0, 12.dp(context), 0)
                    minimumHeight = 36.dp(context)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                approveBtn.setOnClickListener {
                    viewModel.approveReferral(approval.applicantAccountId)
                }
                row.addView(approveBtn)
            }

            CitizenshipStatus.APPROVED, CitizenshipStatus.REFERRER_APPROVED -> {
                val confirmedText = TextView(requireContext()).apply {
                    text = "Confirmed"
                    setTextColor(Color.parseColor("#4CAF50"))
                    textSize = 12f
                }
                row.addView(confirmedText)
            }

            else -> {
                val statusText = TextView(requireContext()).apply {
                    text = approval.status.name
                    setTextColor(Color.parseColor("#78909C"))
                    textSize = 12f
                }
                row.addView(statusText)
            }
        }

        return row
    }

    private fun submitForm() {
        val name = binder.citizenshipNameInput.text?.toString().orEmpty()
        val fatherName = binder.citizenshipFatherNameInput.text?.toString().orEmpty()
        val grandfatherName = binder.citizenshipGrandfatherNameInput.text?.toString().orEmpty()
        val motherName = binder.citizenshipMotherNameInput.text?.toString().orEmpty()
        val tribe = binder.citizenshipTribeInput.text?.toString().orEmpty()
        val region = binder.citizenshipRegionSpinner.selectedItem?.toString().orEmpty()
        val referrer = binder.citizenshipReferrerInput.text?.toString()?.trim()?.ifBlank { null }

        if (name.isBlank()) {
            binder.citizenshipNameLayout.error = getString(R.string.common_name)
            return
        }

        viewModel.submitApplication(name, fatherName, grandfatherName, motherName, tribe, region, referrer)
    }
}
