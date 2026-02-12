package io.novafoundation.nova.feature_assets.presentation.bridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.novafoundation.nova.common.base.BaseViewModel
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.view.ButtonState
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_assets.presentation.send.amount.SendPayload
import io.novafoundation.nova.feature_wallet_api.presentation.model.AssetPayload
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.ext.addressOf
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URL

class BridgeViewModel(
    private val router: AssetsRouter,
    private val resourceManager: ResourceManager,
    private val chainRegistry: ChainRegistry
) : BaseViewModel() {

    companion object {
        // Bridge wallet account ID (derived from seed, same on all chains)
        // Address: 5C5CW7xDmiXtCgfUCbKFF4ViJuCJJQpDZqWQ1mSTjehGzE3p (generic format)
        private const val BRIDGE_ADDRESS_GENERIC = "5C5CW7xDmiXtCgfUCbKFF4ViJuCJJQpDZqWQ1mSTjehGzE3p"

        // Chain IDs
        val POLKADOT_ASSET_HUB_ID = ChainGeneses.POLKADOT_ASSET_HUB
        val PEZKUWI_ASSET_HUB_ID = ChainGeneses.PEZKUWI_ASSET_HUB

        // Utility asset ID (native token)
        const val UTILITY_ASSET_ID = 0

        // Fallback rate: 1 DOT = 3 HEZ (only if CoinGecko unavailable)
        const val FALLBACK_RATE = 3.0

        // Fee: 0.1%
        const val FEE_PERCENT = 0.001

        // Minimums
        const val MIN_DOT = 0.1
        const val MIN_HEZ = 0.3

        // CoinGecko API
        const val COINGECKO_API = "https://api.coingecko.com/api/v3/simple/price?ids=polkadot,hezkurd&vs_currencies=usd"

        // Bridge Status API
        const val BRIDGE_STATUS_API = "http://217.77.6.126:3030/status"
    }

    private val _direction = MutableLiveData(BridgeDirection.DOT_TO_HEZ)
    val direction: LiveData<BridgeDirection> = _direction

    private val _outputAmount = MutableLiveData("0.0")
    val outputAmount: LiveData<String> = _outputAmount

    private val _exchangeRateText = MutableLiveData<String>()
    val exchangeRateText: LiveData<String> = _exchangeRateText

    private val _minimumText = MutableLiveData<String>()
    val minimumText: LiveData<String> = _minimumText

    private val _buttonState = MutableLiveData<ButtonState>()
    val buttonState: LiveData<ButtonState> = _buttonState

    private val _showHezToDotWarning = MutableLiveData(false)
    val showHezToDotWarning: LiveData<Boolean> = _showHezToDotWarning

    private val _hezToDotBlocked = MutableLiveData(false)
    val hezToDotBlocked: LiveData<Boolean> = _hezToDotBlocked

    private val _blockReason = MutableLiveData<String>()
    val blockReason: LiveData<String> = _blockReason

    private val _rateSource = MutableLiveData<String>()
    val rateSource: LiveData<String> = _rateSource

    private var currentAmount: Double = 0.0
    private var dotToHezRate: Double = FALLBACK_RATE
    private var isUsingFallback: Boolean = true
    private var isHezToDotActive: Boolean = false

    init {
        fetchExchangeRate()
        fetchBridgeStatus()
    }

    private fun fetchExchangeRate() {
        launch {
            try {
                val (rate, source) = withContext(Dispatchers.IO) {
                    fetchRateFromCoinGecko()
                }
                dotToHezRate = rate
                isUsingFallback = source == "fallback"
                _rateSource.postValue(source)
                updateUI()
                calculateOutput()
            } catch (e: Exception) {
                // Use fallback
                dotToHezRate = FALLBACK_RATE
                isUsingFallback = true
                _rateSource.postValue("fallback")
                updateUI()
            }
        }
    }

    private fun fetchRateFromCoinGecko(): Pair<Double, String> {
        return try {
            val response = URL(COINGECKO_API).readText()
            val json = JSONObject(response)

            val dotPrice = json.optJSONObject("polkadot")?.optDouble("usd", 0.0) ?: 0.0
            val hezPrice = json.optJSONObject("hezkurd")?.optDouble("usd", 0.0) ?: 0.0

            when {
                dotPrice > 0 && hezPrice > 0 -> {
                    // Both prices available - calculate real rate
                    val rate = dotPrice / hezPrice
                    Pair(rate, "coingecko")
                }
                dotPrice > 0 -> {
                    // Only DOT price - use fallback for HEZ (1 DOT = 3 HEZ means HEZ = DOT/3)
                    Pair(FALLBACK_RATE, "coingecko+fallback")
                }
                else -> {
                    // No prices - use pure fallback
                    Pair(FALLBACK_RATE, "fallback")
                }
            }
        } catch (e: Exception) {
            Pair(FALLBACK_RATE, "fallback")
        }
    }

    private fun fetchBridgeStatus() {
        launch {
            try {
                val active = withContext(Dispatchers.IO) {
                    fetchHezToDotStatus()
                }
                isHezToDotActive = active
                updateHezToDotState()
            } catch (e: Exception) {
                // If API unavailable, assume not active for safety
                isHezToDotActive = false
                updateHezToDotState()
            }
        }
    }

    private fun fetchHezToDotStatus(): Boolean {
        return try {
            val response = URL(BRIDGE_STATUS_API).readText()
            val json = JSONObject(response)
            json.optBoolean("hezToDotActive", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun updateHezToDotState() {
        val dir = _direction.value ?: return
        if (dir == BridgeDirection.HEZ_TO_DOT && !isHezToDotActive) {
            _hezToDotBlocked.postValue(true)
            _blockReason.postValue(resourceManager.getString(R.string.bridge_hez_to_dot_blocked))
        } else {
            _hezToDotBlocked.postValue(false)
            _blockReason.postValue("")
        }
        updateButtonState()
    }

    fun setDirection(newDirection: BridgeDirection) {
        if (_direction.value != newDirection) {
            _direction.value = newDirection
            _showHezToDotWarning.value = newDirection == BridgeDirection.HEZ_TO_DOT
            updateHezToDotState()
            updateUI()
            calculateOutput()
        }
    }

    fun setAmount(amount: Double) {
        currentAmount = amount
        calculateOutput()
        updateButtonState()
    }

    fun swapClicked() {
        val dir = _direction.value ?: return
        if (currentAmount <= 0) return

        launch {
            // Determine which chain and asset to send from based on direction
            val chainId = when (dir) {
                BridgeDirection.DOT_TO_HEZ -> POLKADOT_ASSET_HUB_ID // Send DOT from Polkadot Asset Hub
                BridgeDirection.HEZ_TO_DOT -> PEZKUWI_ASSET_HUB_ID // Send HEZ from Pezkuwi Asset Hub
            }

            // Get the chain to convert address to correct format
            val chain = chainRegistry.getChain(chainId)

            // Convert generic address to chain-specific format
            val accountId = BRIDGE_ADDRESS_GENERIC.toAccountId()
            val bridgeAddress = chain.addressOf(accountId)

            // Create asset payload (utility asset = native token)
            val assetPayload = AssetPayload(chainId, UTILITY_ASSET_ID)

            // Create send payload specifying the origin asset
            val sendPayload = SendPayload.SpecifiedOrigin(assetPayload)

            // Open send screen with pre-filled bridge address AND amount
            router.openSend(sendPayload, bridgeAddress, currentAmount)
        }
    }

    fun backClicked() {
        router.back()
    }

    private fun calculateOutput() {
        val dir = _direction.value ?: return

        val grossOutput = when (dir) {
            BridgeDirection.DOT_TO_HEZ -> currentAmount * dotToHezRate
            BridgeDirection.HEZ_TO_DOT -> currentAmount / dotToHezRate
        }

        // Apply fee
        val netOutput = grossOutput * (1 - FEE_PERCENT)

        _outputAmount.value = if (netOutput > 0) {
            BigDecimal(netOutput).setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        } else {
            "0.0"
        }
    }

    private fun updateUI() {
        val dir = _direction.value ?: return

        val rateFormatted = BigDecimal(dotToHezRate).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val reverseRateFormatted = BigDecimal(1.0 / dotToHezRate).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        when (dir) {
            BridgeDirection.DOT_TO_HEZ -> {
                _exchangeRateText.value = "1 DOT = $rateFormatted HEZ"
                _minimumText.value = "$MIN_DOT DOT"
            }
            BridgeDirection.HEZ_TO_DOT -> {
                _exchangeRateText.value = "1 HEZ = $reverseRateFormatted DOT"
                _minimumText.value = "$MIN_HEZ HEZ"
            }
        }

        updateButtonState()
    }

    private fun updateButtonState() {
        val dir = _direction.value ?: return
        val minimum = when (dir) {
            BridgeDirection.DOT_TO_HEZ -> MIN_DOT
            BridgeDirection.HEZ_TO_DOT -> MIN_HEZ
        }

        _buttonState.value = when {
            currentAmount <= 0 -> ButtonState.DISABLED
            currentAmount < minimum -> ButtonState.DISABLED
            dir == BridgeDirection.HEZ_TO_DOT && !isHezToDotActive -> ButtonState.DISABLED
            else -> ButtonState.NORMAL
        }
    }

    fun refreshBridgeStatus() {
        fetchBridgeStatus()
    }
}
