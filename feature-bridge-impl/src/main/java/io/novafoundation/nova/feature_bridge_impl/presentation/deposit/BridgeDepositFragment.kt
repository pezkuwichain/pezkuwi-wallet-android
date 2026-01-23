package io.novafoundation.nova.feature_bridge_impl.presentation.deposit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.novafoundation.nova.feature_bridge_api.domain.model.BridgeConfig
import io.novafoundation.nova.feature_bridge_impl.databinding.FragmentBridgeDepositBinding
import kotlinx.coroutines.launch

/**
 * Bridge Deposit Screen
 *
 * Shows the Polkadot Asset Hub address where users should send USDT
 * to receive wUSDT on Pezkuwi Asset Hub.
 */
class BridgeDepositFragment : Fragment() {

    private var _binding: FragmentBridgeDepositBinding? = null
    private val binding get() = _binding!!

    private val bridgeConfig = BridgeConfig.DEFAULT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBridgeDepositBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        generateQRCode()
    }

    private fun setupUI() {
        with(binding) {
            // Set bridge address
            bridgeAddressText.text = bridgeConfig.polkadotDepositAddress

            // Set info text
            minDepositText.text = "Minimum: ${bridgeConfig.minDeposit} USDT"
            feeText.text = "Fee: ${bridgeConfig.feePercentage}"

            // Copy button
            copyAddressButton.setOnClickListener {
                copyToClipboard(bridgeConfig.polkadotDepositAddress)
            }

            // Address text click also copies
            bridgeAddressText.setOnClickListener {
                copyToClipboard(bridgeConfig.polkadotDepositAddress)
            }

            // Back button
            backButton.setOnClickListener {
                requireActivity().onBackPressed()
            }
        }
    }

    private fun generateQRCode() {
        lifecycleScope.launch {
            try {
                val qrCodeWriter = QRCodeWriter()
                val bitMatrix = qrCodeWriter.encode(
                    bridgeConfig.polkadotDepositAddress,
                    BarcodeFormat.QR_CODE,
                    512,
                    512
                )

                val width = bitMatrix.width
                val height = bitMatrix.height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bitmap.setPixel(
                            x, y,
                            if (bitMatrix.get(x, y)) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE
                        )
                    }
                }

                binding.qrCodeImage.setImageBitmap(bitmap)
            } catch (e: Exception) {
                // QR code generation failed, hide the image
                binding.qrCodeImage.visibility = View.GONE
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Bridge Address", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Address copied!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = BridgeDepositFragment()
    }
}
