package io.novafoundation.nova.feature_bridge_impl.presentation.status

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.novafoundation.nova.feature_bridge_api.domain.model.BridgeConfig
import io.novafoundation.nova.feature_bridge_api.domain.model.BridgeStatus
import io.novafoundation.nova.feature_bridge_impl.databinding.FragmentBridgeStatusBinding
import java.math.BigDecimal

/**
 * Bridge Status Screen
 *
 * Shows the current bridge backing status, reserves, and transparency info.
 */
class BridgeStatusFragment : Fragment() {

    private var _binding: FragmentBridgeStatusBinding? = null
    private val binding get() = _binding!!

    private val bridgeConfig = BridgeConfig.DEFAULT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBridgeStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadStatus()
    }

    private fun setupUI() {
        with(binding) {
            backButton.setOnClickListener {
                requireActivity().onBackPressed()
            }
        }
    }

    private fun loadStatus() {
        // In production, this would fetch real data from the chain
        // For now, show placeholder data
        val status = BridgeStatus(
            totalUsdtBacking = BigDecimal("0"),
            totalWusdtCirculating = BigDecimal("0"),
            isOperational = true,
            lastSyncTimestamp = System.currentTimeMillis()
        )

        displayStatus(status)
    }

    private fun displayStatus(status: BridgeStatus) {
        with(binding) {
            // Backing ratio
            backingRatioText.text = "${status.backingRatio.setScale(2)}%"
            backingRatioText.setTextColor(
                if (status.isFullyBacked) {
                    resources.getColor(io.novafoundation.nova.feature_bridge_impl.R.color.bridge_success, null)
                } else {
                    resources.getColor(io.novafoundation.nova.feature_bridge_impl.R.color.bridge_error, null)
                }
            )

            // Backing details
            usdtBackingText.text = "${status.totalUsdtBacking} USDT"
            wusdtCirculatingText.text = "${status.totalWusdtCirculating} wUSDT"
            reserveText.text = "${status.reserve} USDT"

            // Bridge config
            polkadotAddressText.text = bridgeConfig.polkadotDepositAddress
            pezkuwiAddressText.text = bridgeConfig.pezkuwiAddress
            feeText.text = bridgeConfig.feePercentage
            minDepositText.text = "${bridgeConfig.minDeposit} USDT"

            // Status
            statusIndicator.setBackgroundColor(
                if (status.isOperational) {
                    resources.getColor(io.novafoundation.nova.feature_bridge_impl.R.color.bridge_success, null)
                } else {
                    resources.getColor(io.novafoundation.nova.feature_bridge_impl.R.color.bridge_error, null)
                }
            )
            statusText.text = if (status.isOperational) "Operational" else "Offline"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = BridgeStatusFragment()
    }
}
