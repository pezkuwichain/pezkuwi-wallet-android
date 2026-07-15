package io.novafoundation.nova.feature_assets.presentation.bridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.novafoundation.nova.common.base.BaseViewModel
import io.novafoundation.nova.common.presentation.AssetIconProvider
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.utils.Event
import io.novafoundation.nova.common.utils.images.Icon
import io.novafoundation.nova.common.view.ButtonState
import io.novafoundation.nova.common.view.ExecutionTimerView
import io.novafoundation.nova.feature_account_api.presenatation.chain.getAssetIconOrFallback
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.domain.WalletInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeMultisigConstants
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeMultisigInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeSignerState
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_assets.presentation.send.amount.SendPayload
import io.novafoundation.nova.feature_wallet_api.presentation.model.AssetPayload
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.ext.addressOf
import io.novafoundation.nova.runtime.ext.displayNameWithAssetStandard
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.seconds

/**
 * DOT<->HEZ used to be a second pair here, retired 2026-07 in favor of the multisig-custodied
 * USDT<->wUSDT pair only - the legacy single-key bot that executed it (and every other swap on
 * this screen) was replaced by the detect-only Rust listener + pwap-web multisig approval flow
 * (see /home/myhez/res/validators-tiki.md). Kept the BridgePair/pairOptions/picker structure
 * (rather than collapsing to a single hardcoded pair) since it costs nothing and is exactly the
 * seam a future new pair would reuse.
 */
class BridgeViewModel(
    private val router: AssetsRouter,
    private val resourceManager: ResourceManager,
    private val chainRegistry: ChainRegistry,
    private val assetIconProvider: AssetIconProvider,
    private val walletInteractor: WalletInteractor,
    private val bridgeMultisigInteractor: BridgeMultisigInteractor
) : BaseViewModel() {

    companion object {
        /** The real 3-of-5 multisig custody account (generic SS58, prefix 42) - swaps must be
         *  sent here, not to any single-key address, so usdt-bridge's listener (which only
         *  watches this account on both chains) actually detects them. This constant used to be
         *  a leftover pre-migration value (the retired single-key legacy bot's own address,
         *  "5C5CW7xDmiXtCgfUCbKFF4ViJuCJJQpDZqWQ1mSTjehGzE3p") that nobody updated when the
         *  custody model changed - confirmed live: a real user swap sent there landed at that
         *  dead address (1.0 USDT, recovered via the still-intact legacy seed) and was never
         *  detected by anything. See BridgeMultisigConstants.MULTISIG_ADDRESS for the same
         *  address used by the signer-renewal flow on this same screen. */
        private const val BRIDGE_ADDRESS_GENERIC = BridgeMultisigConstants.MULTISIG_ADDRESS

        val POLKADOT_ASSET_HUB_ID = ChainGeneses.POLKADOT_ASSET_HUB
        val PEZKUWI_ASSET_HUB_ID = ChainGeneses.PEZKUWI_ASSET_HUB

        // USDT asset IDs in chain config
        const val POLKADOT_USDT_ASSET_ID = 1 // assetId in chains.json for Polkadot AH
        const val PEZKUWI_USDT_ASSET_ID = 1000 // assetId in chains.json for Pezkuwi AH

        const val FEE_PERCENT = 0.001
        const val MIN_USDT = 1.0

        // USDT has 6 decimals on both Polkadot and Pezkuwi Asset Hub.
        val USDT_DECIMALS_DIVISOR: BigDecimal = BigDecimal.TEN.pow(6)

        /** How long to actively watch the destination balance before admitting we can't confirm
         *  completion yet - not a claim about how long the bridge itself actually takes. */
        val DEPOSIT_WAIT_TIMEOUT = 90.seconds
    }

    private val _pair = MutableLiveData(BridgePair.USDT)
    val pair: LiveData<BridgePair> = _pair

    private val _direction = MutableLiveData(BridgeDirection.USDT_TO_WUSDT)
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

    private val _signButtonVisible = MutableLiveData(false)
    val signButtonVisible: LiveData<Boolean> = _signButtonVisible

    private val _signButtonRed = MutableLiveData(false)
    val signButtonRed: LiveData<Boolean> = _signButtonRed

    private val _signButtonEnabled = MutableLiveData(false)
    val signButtonEnabled: LiveData<Boolean> = _signButtonEnabled

    private val _signButtonLabel = MutableLiveData("")
    val signButtonLabel: LiveData<String> = _signButtonLabel

    /** Null = hidden. Driven by an actual destination-balance observation after Swap is tapped -
     *  never just a cosmetic countdown that reports "done" regardless of whether funds arrived. */
    private val _depositWaitState = MutableLiveData<ExecutionTimerView.State?>(null)
    val depositWaitState: LiveData<ExecutionTimerView.State?> = _depositWaitState

    private val _depositWaitLabelVisible = MutableLiveData(false)
    val depositWaitLabelVisible: LiveData<Boolean> = _depositWaitLabelVisible

    private var depositWaitJob: Job? = null

    private val _fromCard = MutableLiveData<BridgeAssetCardUi>()
    val fromCard: LiveData<BridgeAssetCardUi> = _fromCard

    private val _toCard = MutableLiveData<BridgeAssetCardUi>()
    val toCard: LiveData<BridgeAssetCardUi> = _toCard

    private val _pairOptions = MutableLiveData<List<BridgePairUi>>(emptyList())
    val pairOptions: LiveData<List<BridgePairUi>> = _pairOptions

    private val _maxAmountDisplay = MutableLiveData<String?>(null)
    val maxAmountDisplay: LiveData<String?> = _maxAmountDisplay

    private val _insufficientBalanceError = MutableLiveData<String?>(null)
    val insufficientBalanceError: LiveData<String?> = _insufficientBalanceError

    private val _fillAmountEvent = MutableLiveData<Event<String>>()
    val fillAmountEvent: LiveData<Event<String>> = _fillAmountEvent

    private var currentAmount: Double = 0.0

    /** Real USDT the multisig actually holds on Polkadot Asset Hub - see
     *  BridgeMultisigInteractor.getPolkadotUsdtReserve for why this replaced a dead external
     *  status check that always reported "inactive" regardless of the real reserve. */
    private var polkadotUsdtReserve: BigDecimal = BigDecimal.ZERO
    private var availableBalance: BigDecimal = BigDecimal.ZERO
    private var balanceJob: Job? = null

    init {
        fetchReserveStatus()
        refreshSignerState()
        updateCards()
        loadPairOptions()
        updateUI()
    }

    fun setPair(newPair: BridgePair) {
        if (_pair.value != newPair) {
            _pair.value = newPair
            // Reset direction to left (forward) when switching pair
            _direction.value = BridgeDirection.USDT_TO_WUSDT
            updateUI()
            calculateOutput()
            updateWarningState()
            updateCards()
        }
    }

    fun setDirectionLeft() {
        val newDir = BridgeDirection.USDT_TO_WUSDT
        if (_direction.value != newDir) {
            _direction.value = newDir
            updateUI()
            calculateOutput()
            updateWarningState()
            updateCards()
        }
    }

    fun setDirectionRight() {
        val newDir = BridgeDirection.WUSDT_TO_USDT
        if (_direction.value != newDir) {
            _direction.value = newDir
            updateUI()
            calculateOutput()
            updateWarningState()
            updateCards()
        }
    }

    fun setAmount(amount: Double) {
        currentAmount = amount
        calculateOutput()
        updateInsufficientBalanceState()
        updateWarningState() // re-check the entered amount against the cached reserve
    }

    fun maxClicked() {
        _fillAmountEvent.value = Event(availableBalance.stripTrailingZeros().toPlainString())
    }

    fun swapClicked() {
        val dir = _direction.value ?: return
        if (currentAmount <= 0) return

        val chainId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> POLKADOT_ASSET_HUB_ID
            BridgeDirection.WUSDT_TO_USDT -> PEZKUWI_ASSET_HUB_ID
        }

        val assetId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> POLKADOT_USDT_ASSET_ID
            BridgeDirection.WUSDT_TO_USDT -> PEZKUWI_USDT_ASSET_ID
        }

        val destChainId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> PEZKUWI_ASSET_HUB_ID
            BridgeDirection.WUSDT_TO_USDT -> POLKADOT_ASSET_HUB_ID
        }

        val destAssetId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> PEZKUWI_USDT_ASSET_ID
            BridgeDirection.WUSDT_TO_USDT -> POLKADOT_USDT_ASSET_ID
        }

        val expectedAmount = currentAmount

        launch {
            val chain = chainRegistry.getChain(chainId)
            val accountId = BRIDGE_ADDRESS_GENERIC.toAccountId()
            val bridgeAddress = chain.addressOf(accountId)

            val assetPayload = AssetPayload(chainId, assetId)
            val sendPayload = SendPayload.SpecifiedOrigin(assetPayload)

            router.openSend(sendPayload, bridgeAddress, currentAmount)

            observeDepositCompletion(destChainId, destAssetId, expectedAmount)
        }
    }

    /** Watches the DESTINATION balance for a real increase after a swap is submitted, rather than
     *  a cosmetic timer that reports "done" regardless of whether funds actually arrived - there is
     *  no completion callback from the generic Send flow this screen delegates to (it just pops
     *  back to the previous screen on success), so balance-delta is the only honest signal
     *  available. Bounded to a reasonable wait; if it elapses without a confirmed increase, this
     *  says so plainly rather than implying success or failure it can't actually confirm - the
     *  swap may just need manual 3-of-5 review, which can take longer.
     */
    private fun observeDepositCompletion(destChainId: String, destAssetId: Int, expectedAmount: Double) {
        depositWaitJob?.cancel()
        depositWaitJob = launch {
            val balanceBefore = try {
                walletInteractor.assetFlow(destChainId, destAssetId).first().transferable
            } catch (e: Exception) {
                return@launch // Can't observe reliably - don't show a misleading progress state.
            }

            _depositWaitLabelVisible.postValue(true)
            _depositWaitState.postValue(ExecutionTimerView.State.CountdownTimer(DEPOSIT_WAIT_TIMEOUT))

            val minExpectedIncrease = BigDecimal.valueOf(expectedAmount * (1 - FEE_PERCENT * 2)) // fee + rounding slack

            val confirmed = withTimeoutOrNull(DEPOSIT_WAIT_TIMEOUT.inWholeMilliseconds) {
                walletInteractor.assetFlow(destChainId, destAssetId)
                    .first { it.transferable - balanceBefore >= minExpectedIncrease }
            } != null

            if (confirmed) {
                _depositWaitState.postValue(ExecutionTimerView.State.Success)
            } else {
                // Not a failure - just not confirmed within the wait window. Hide the timer and
                // say so plainly instead of showing a false success or a false error icon.
                _depositWaitState.postValue(null)
                _depositWaitLabelVisible.postValue(false)
                _warningBlocked.postValue(false)
                _showWarning.postValue(true)
                _warningText.postValue(resourceManager.getString(R.string.bridge_deposit_pending_message))
            }
        }
    }

    fun backClicked() {
        router.back()
    }

    private fun fetchReserveStatus() {
        launch {
            polkadotUsdtReserve = try {
                val raw = bridgeMultisigInteractor.getPolkadotUsdtReserve()
                BigDecimal(raw).divide(USDT_DECIMALS_DIVISOR)
            } catch (e: Exception) {
                BigDecimal.ZERO
            }
            updateWarningState()
        }
    }

    private fun updateWarningState() {
        val dir = _direction.value ?: return

        when (dir) {
            BridgeDirection.WUSDT_TO_USDT -> {
                val requested = BigDecimal.valueOf(currentAmount)
                val exceedsReserve = currentAmount > 0 && requested > polkadotUsdtReserve
                _showWarning.postValue(exceedsReserve)
                if (exceedsReserve) {
                    _warningBlocked.postValue(true)
                    _warningText.postValue(
                        resourceManager.getString(
                            R.string.bridge_wusdt_to_usdt_blocked,
                            polkadotUsdtReserve.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
                        )
                    )
                } else {
                    _warningBlocked.postValue(false)
                    _warningText.postValue("")
                }
            }
            else -> {
                _showWarning.postValue(false)
            }
        }
        updateButtonState()
    }

    private fun calculateOutput() {
        val netOutput = currentAmount * (1 - FEE_PERCENT) // 1:1, fee-adjusted

        _outputAmount.value = if (netOutput > 0) {
            BigDecimal(netOutput).setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        } else {
            "0.0"
        }
    }

    private fun updateUI() {
        _exchangeRateText.value = "1:1 (fee 0.1%)"
        _minimumText.value = "$MIN_USDT USDT"

        updateButtonState()
    }

    private fun updateButtonState() {
        val dir = _direction.value ?: return

        val requested = BigDecimal.valueOf(currentAmount)
        _buttonState.value = when {
            currentAmount <= 0 -> ButtonState.DISABLED
            currentAmount < MIN_USDT -> ButtonState.DISABLED
            requested > availableBalance -> ButtonState.DISABLED
            dir == BridgeDirection.WUSDT_TO_USDT && requested > polkadotUsdtReserve -> ButtonState.DISABLED
            else -> ButtonState.NORMAL
        }
    }

    private fun updateInsufficientBalanceState() {
        _insufficientBalanceError.value = if (currentAmount > 0 && BigDecimal.valueOf(currentAmount) > availableBalance) {
            resourceManager.getString(R.string.bridge_insufficient_balance)
        } else {
            null
        }
    }

    fun refreshBridgeStatus() {
        fetchReserveStatus()
        refreshSignerState()
    }

    fun refreshSignerState() {
        launch {
            val state = bridgeMultisigInteractor.getSignerState()
            applySignerState(state)
        }
    }

    fun signClicked() {
        if (_signButtonEnabled.value != true) return

        _signButtonEnabled.postValue(false)
        _signButtonLabel.postValue(resourceManager.getString(R.string.bridge_sign_in_progress))

        launch {
            bridgeMultisigInteractor.submitRenewalSignature()
                .onFailure {
                    _signButtonLabel.postValue(resourceManager.getString(R.string.bridge_sign_error))
                }
            refreshSignerState()
        }
    }

    private fun applySignerState(state: BridgeSignerState?) {
        if (state == null) {
            _signButtonVisible.postValue(false)
            return
        }

        _signButtonVisible.postValue(true)

        when {
            !state.needsRenewal -> {
                _signButtonRed.postValue(false)
                _signButtonEnabled.postValue(false)
                _signButtonLabel.postValue(resourceManager.getString(R.string.bridge_sign_status_ok, state.signatoryRole))
            }
            state.alreadySignedPendingRenewal -> {
                _signButtonRed.postValue(true)
                _signButtonEnabled.postValue(false)
                _signButtonLabel.postValue(resourceManager.getString(R.string.bridge_sign_waiting_others))
            }
            else -> {
                _signButtonRed.postValue(true)
                _signButtonEnabled.postValue(true)
                _signButtonLabel.postValue(resourceManager.getString(R.string.bridge_sign_button, state.signatoryRole))
            }
        }
    }

    private fun updateCards() {
        val dir = _direction.value ?: return

        // Same chainId/assetId mapping already used by swapClicked() to resolve the origin side -
        // mirrored here (plus its destination counterpart) purely to display logos/names, no new business rule.
        val originChainId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> POLKADOT_ASSET_HUB_ID
            BridgeDirection.WUSDT_TO_USDT -> PEZKUWI_ASSET_HUB_ID
        }
        val destChainId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> PEZKUWI_ASSET_HUB_ID
            BridgeDirection.WUSDT_TO_USDT -> POLKADOT_ASSET_HUB_ID
        }
        val originAssetId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> POLKADOT_USDT_ASSET_ID
            BridgeDirection.WUSDT_TO_USDT -> PEZKUWI_USDT_ASSET_ID
        }
        val destAssetId = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> PEZKUWI_USDT_ASSET_ID
            BridgeDirection.WUSDT_TO_USDT -> POLKADOT_USDT_ASSET_ID
        }

        launch {
            _fromCard.value = cardUiFor(originChainId, originAssetId)
            _toCard.value = cardUiFor(destChainId, destAssetId)
        }

        observeOriginBalance(originChainId, originAssetId)
    }

    private fun observeOriginBalance(chainId: String, assetId: Int) {
        balanceJob?.cancel()
        balanceJob = launch {
            walletInteractor.assetFlow(chainId, assetId).collect { asset ->
                availableBalance = asset.transferable
                _maxAmountDisplay.postValue(
                    "${availableBalance.setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()} ${asset.token.configuration.symbol.value}"
                )
                updateInsufficientBalanceState()
                updateButtonState()
            }
        }
    }

    private fun loadPairOptions() {
        launch {
            val usdtIcon = cardUiFor(POLKADOT_ASSET_HUB_ID, POLKADOT_USDT_ASSET_ID).assetIcon

            _pairOptions.value = listOf(
                BridgePairUi(BridgePair.USDT, usdtIcon, resourceManager.getString(R.string.bridge_pair_usdt))
            )
        }
    }

    private suspend fun cardUiFor(chainId: String, assetId: Int): BridgeAssetCardUi {
        val chain = chainRegistry.getChain(chainId)
        val asset = chain.assetsById.getValue(assetId)

        return BridgeAssetCardUi(
            assetIcon = assetIconProvider.getAssetIconOrFallback(asset),
            chainIconUrl = chain.icon,
            symbol = asset.symbol.value,
            chainName = chain.displayNameWithAssetStandard()
        )
    }
}

data class BridgeAssetCardUi(
    val assetIcon: Icon,
    val chainIconUrl: String?,
    val symbol: String,
    val chainName: String
)
