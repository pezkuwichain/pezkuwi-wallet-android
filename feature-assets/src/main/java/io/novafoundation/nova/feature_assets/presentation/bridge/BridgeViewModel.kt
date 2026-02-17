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
        private const val BRIDGE_ADDRESS_GENERIC = "5C5CW7xDmiXtCgfUCbKFF4ViJuCJJQpDZqWQ1mSTjehGzE3p"

        val POLKADOT_ASSET_HUB_ID = ChainGeneses.POLKADOT_ASSET_HUB
        val PEZKUWI_ASSET_HUB_ID = ChainGeneses.PEZKUWI_ASSET_HUB

        const val UTILITY_ASSET_ID = 0

        // USDT asset IDs in chain config
        const val POLKADOT_USDT_ASSET_ID = 1    // assetId in chains.json for Polkadot AH
        const val PEZKUWI_USDT_ASSET_ID = 1000  // assetId in chains.json for Pezkuwi AH

        const val FALLBACK_RATE = 3.0
        const val FEE_PERCENT = 0.001
        const val MIN_DOT = 0.1
        const val MIN_HEZ = 0.3
        const val MIN_USDT = 1.0

        const val COINGECKO_API = "https://api.coingecko.com/api/v3/simple/price?ids=polkadot,hezkurd&vs_currencies=usd"
        const val BRIDGE_STATUS_API = "http://217.77.6.126:3030/status"
    }

    private val _pair = MutableLiveData(BridgePair.DOT_HEZ)
    val pair: LiveData<BridgePair> = _pair

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

    private val _showWarning = MutableLiveData(false)
    val showWarning: LiveData<Boolean> = _showWarning

    private val _warningBlocked = MutableLiveData(false)
    val warningBlocked: LiveData<Boolean> = _warningBlocked

    private val _warningText = MutableLiveData<String>()
    val warningText: LiveData<String> = _warningText

    private var currentAmount: Double = 0.0
    private var dotToHezRate: Double = FALLBACK_RATE
    private var isHezToDotActive: Boolean = false
    private var isWusdtToUsdtActive: Boolean = false

    init {
        fetchExchangeRate()
        fetchBridgeStatus()
    }

    fun setPair(newPair: BridgePair) {
        if (_pair.value != newPair) {
            _pair.value = newPair
            // Reset direction to left (forward) when switching pair
            _direction.value = when (newPair) {
                BridgePair.DOT_HEZ -> BridgeDirection.DOT_TO_HEZ
                BridgePair.USDT -> BridgeDirection.USDT_TO_WUSDT
            }
            updateUI()
            calculateOutput()
            updateWarningState()
        }
    }

    fun setDirectionLeft() {
        val newDir = when (_pair.value) {
            BridgePair.DOT_HEZ -> BridgeDirection.DOT_TO_HEZ
            BridgePair.USDT -> BridgeDirection.USDT_TO_WUSDT
            null -> BridgeDirection.DOT_TO_HEZ
        }
        if (_direction.value != newDir) {
            _direction.value = newDir
            updateUI()
            calculateOutput()
            updateWarningState()
        }
    }

    fun setDirectionRight() {
        val newDir = when (_pair.value) {
            BridgePair.DOT_HEZ -> BridgeDirection.HEZ_TO_DOT
            BridgePair.USDT -> BridgeDirection.WUSDT_TO_USDT
            null -> BridgeDirection.HEZ_TO_DOT
        }
        if (_direction.value != newDir) {
            _direction.value = newDir
            updateUI()
            calculateOutput()
            updateWarningState()
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
            val chainId = when (dir) {
                BridgeDirection.DOT_TO_HEZ -> POLKADOT_ASSET_HUB_ID
                BridgeDirection.HEZ_TO_DOT -> PEZKUWI_ASSET_HUB_ID
                BridgeDirection.USDT_TO_WUSDT -> POLKADOT_ASSET_HUB_ID
                BridgeDirection.WUSDT_TO_USDT -> PEZKUWI_ASSET_HUB_ID
            }

            val assetId = when (dir) {
                BridgeDirection.DOT_TO_HEZ -> UTILITY_ASSET_ID
                BridgeDirection.HEZ_TO_DOT -> UTILITY_ASSET_ID
                BridgeDirection.USDT_TO_WUSDT -> POLKADOT_USDT_ASSET_ID
                BridgeDirection.WUSDT_TO_USDT -> PEZKUWI_USDT_ASSET_ID
            }

            val chain = chainRegistry.getChain(chainId)
            val accountId = BRIDGE_ADDRESS_GENERIC.toAccountId()
            val bridgeAddress = chain.addressOf(accountId)

            val assetPayload = AssetPayload(chainId, assetId)
            val sendPayload = SendPayload.SpecifiedOrigin(assetPayload)

            router.openSend(sendPayload, bridgeAddress, currentAmount)
        }
    }

    fun backClicked() {
        router.back()
    }

    private fun fetchExchangeRate() {
        launch {
            try {
                val (rate, _) = withContext(Dispatchers.IO) {
                    fetchRateFromCoinGecko()
                }
                dotToHezRate = rate
                updateUI()
                calculateOutput()
            } catch (e: Exception) {
                dotToHezRate = FALLBACK_RATE
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
                dotPrice > 0 && hezPrice > 0 -> Pair(dotPrice / hezPrice, "coingecko")
                else -> Pair(FALLBACK_RATE, "fallback")
            }
        } catch (e: Exception) {
            Pair(FALLBACK_RATE, "fallback")
        }
    }

    private fun fetchBridgeStatus() {
        launch {
            try {
                val (hezToDot, wusdtToUsdt) = withContext(Dispatchers.IO) {
                    fetchStatusFromApi()
                }
                isHezToDotActive = hezToDot
                isWusdtToUsdtActive = wusdtToUsdt
                updateWarningState()
            } catch (e: Exception) {
                isHezToDotActive = false
                isWusdtToUsdtActive = false
                updateWarningState()
            }
        }
    }

    private fun fetchStatusFromApi(): Pair<Boolean, Boolean> {
        return try {
            val response = URL(BRIDGE_STATUS_API).readText()
            val json = JSONObject(response)
            val hezToDot = json.optBoolean("hezToDotActive", false)
            val wusdtToUsdt = json.optBoolean("wusdtToUsdtActive", false)
            Pair(hezToDot, wusdtToUsdt)
        } catch (e: Exception) {
            Pair(false, false)
        }
    }

    private fun updateWarningState() {
        val dir = _direction.value ?: return

        when (dir) {
            BridgeDirection.HEZ_TO_DOT -> {
                _showWarning.postValue(true)
                if (!isHezToDotActive) {
                    _warningBlocked.postValue(true)
                    _warningText.postValue(resourceManager.getString(R.string.bridge_hez_to_dot_blocked))
                } else {
                    _warningBlocked.postValue(false)
                    _warningText.postValue(resourceManager.getString(R.string.bridge_hez_to_dot_warning))
                }
            }
            BridgeDirection.WUSDT_TO_USDT -> {
                _showWarning.postValue(true)
                if (!isWusdtToUsdtActive) {
                    _warningBlocked.postValue(true)
                    _warningText.postValue(resourceManager.getString(R.string.bridge_wusdt_to_usdt_blocked))
                } else {
                    _warningBlocked.postValue(false)
                    _warningText.postValue("")
                    _showWarning.postValue(false)
                }
            }
            else -> {
                _showWarning.postValue(false)
            }
        }
        updateButtonState()
    }

    private fun calculateOutput() {
        val dir = _direction.value ?: return

        val grossOutput = when (dir) {
            BridgeDirection.DOT_TO_HEZ -> currentAmount * dotToHezRate
            BridgeDirection.HEZ_TO_DOT -> currentAmount / dotToHezRate
            BridgeDirection.USDT_TO_WUSDT -> currentAmount  // 1:1
            BridgeDirection.WUSDT_TO_USDT -> currentAmount  // 1:1
        }

        val netOutput = grossOutput * (1 - FEE_PERCENT)

        _outputAmount.value = if (netOutput > 0) {
            BigDecimal(netOutput).setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        } else {
            "0.0"
        }
    }

    private fun updateUI() {
        val dir = _direction.value ?: return

        when (dir) {
            BridgeDirection.DOT_TO_HEZ -> {
                val rateFormatted = BigDecimal(dotToHezRate).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                _exchangeRateText.value = "1 DOT = $rateFormatted HEZ"
                _minimumText.value = "$MIN_DOT DOT"
            }
            BridgeDirection.HEZ_TO_DOT -> {
                val reverseRate = BigDecimal(1.0 / dotToHezRate).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                _exchangeRateText.value = "1 HEZ = $reverseRate DOT"
                _minimumText.value = "$MIN_HEZ HEZ"
            }
            BridgeDirection.USDT_TO_WUSDT, BridgeDirection.WUSDT_TO_USDT -> {
                _exchangeRateText.value = "1:1 (fee 0.1%)"
                _minimumText.value = "$MIN_USDT USDT"
            }
        }

        updateButtonState()
    }

    private fun updateButtonState() {
        val dir = _direction.value ?: return
        val minimum = when (dir) {
            BridgeDirection.DOT_TO_HEZ -> MIN_DOT
            BridgeDirection.HEZ_TO_DOT -> MIN_HEZ
            BridgeDirection.USDT_TO_WUSDT, BridgeDirection.WUSDT_TO_USDT -> MIN_USDT
        }

        _buttonState.value = when {
            currentAmount <= 0 -> ButtonState.DISABLED
            currentAmount < minimum -> ButtonState.DISABLED
            dir == BridgeDirection.HEZ_TO_DOT && !isHezToDotActive -> ButtonState.DISABLED
            dir == BridgeDirection.WUSDT_TO_USDT && !isWusdtToUsdtActive -> ButtonState.DISABLED
            else -> ButtonState.NORMAL
        }
    }

    fun refreshBridgeStatus() {
        fetchBridgeStatus()
    }
}
