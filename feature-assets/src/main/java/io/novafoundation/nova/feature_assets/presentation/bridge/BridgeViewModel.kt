package io.novafoundation.nova.feature_assets.presentation.bridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.novafoundation.nova.common.base.BaseViewModel
import io.novafoundation.nova.common.presentation.AssetIconProvider
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.utils.Event
import io.novafoundation.nova.common.utils.images.Icon
import io.novafoundation.nova.common.view.ButtonState
import io.novafoundation.nova.feature_account_api.presenatation.chain.getAssetIconOrFallback
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.domain.WalletInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeMultisigConstants
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeMultisigInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeSignerState
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_assets.presentation.bridge.execution.BridgeExecutionPayload
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.ext.addressOf
import io.novafoundation.nova.runtime.ext.displayNameWithAssetStandard
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * DOT<->HEZ used to be a second pair here, retired 2026-07 in favor of the multisig-custodied
 * USDT<->wUSDT pair only - the legacy single-key bot that executed it (and every other swap on
 * this screen) was replaced by the detect-only Rust listener + pwap-web multisig approval flow
 * (see /home/myhez/res/validators-tiki.md). Kept the BridgePair/pairOptions/picker structure
 * (rather than collapsing to a single hardcoded pair) since it costs nothing and is exactly the
 * seam a future new pair would reuse.
 *
 * This screen is deliberately input-only: it has no submit/execution logic at all, the same way
 * the app's own Swap flow keeps amount entry and execution as separate screens/ViewModels. See
 * BridgeExecutionViewModel for the actual submit + destination-wait, reached via swapClicked()
 * below. Amount-vs-balance/reserve validation here is a live *display* concern (the error text
 * under the input, the button's enabled look) - swapClicked() re-checks the same conditions for
 * real right before navigating, which is the actual confirmation gate.
 */
class BridgeViewModel(
    private val router: AssetsRouter,
    private val resourceManager: ResourceManager,
    private val chainRegistry: ChainRegistry,
    private val assetIconProvider: AssetIconProvider,
    private val walletInteractor: WalletInteractor,
    private val bridgeMultisigInteractor: BridgeMultisigInteractor,
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

    /** Same as the sign* set above but for the automation key's Polkadot-side USDT approval -
     *  see BridgeMultisigInteractor.getPolkadotSignerState for why this is currently always
     *  offered (approval has never been granted at all). A separate row rather than merging with
     *  the wUSDT one since a signatory may need to renew one leg without the other. */
    private val _polkadotSignButtonVisible = MutableLiveData(false)
    val polkadotSignButtonVisible: LiveData<Boolean> = _polkadotSignButtonVisible

    private val _polkadotSignButtonRed = MutableLiveData(false)
    val polkadotSignButtonRed: LiveData<Boolean> = _polkadotSignButtonRed

    private val _polkadotSignButtonEnabled = MutableLiveData(false)
    val polkadotSignButtonEnabled: LiveData<Boolean> = _polkadotSignButtonEnabled

    private val _polkadotSignButtonLabel = MutableLiveData("")
    val polkadotSignButtonLabel: LiveData<String> = _polkadotSignButtonLabel

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

    /** True when the entered amount exceeds the automation key's current on-chain approval for
     *  this direction - real funds exist and the transfer WILL succeed, it just won't auto-pay
     *  and needs 3-of-5 signatory review. Distinct from showWarning/warningBlocked (which cover
     *  the harder "not enough real reserve at all" case, unresolvable by any signature) - this
     *  one is resolvable, so the UI offers an explicit opt-in instead of a flat block. */
    private val _consentRequired = MutableLiveData(false)
    val consentRequired: LiveData<Boolean> = _consentRequired

    private val _consentChecked = MutableLiveData(false)
    val consentChecked: LiveData<Boolean> = _consentChecked

    fun consentCheckboxToggled(checked: Boolean) {
        _consentChecked.value = checked
        updateButtonState()
    }

    private var currentAmount: Double = 0.0

    /** Real USDT the multisig actually holds on Polkadot Asset Hub - see
     *  BridgeMultisigInteractor.getPolkadotUsdtReserve for why this replaced a dead external
     *  status check that always reported "inactive" regardless of the real reserve. */
    private var polkadotUsdtReserve: BigDecimal = BigDecimal.ZERO

    /** Remaining on-chain approval (BridgeMultisigInteractor.getWusdtRemainingAllowance /
     *  getPolkadotUsdtRemainingAllowance), one per leg - the deterministic fact that decides
     *  whether a given amount can possibly auto-pay in that direction, independent of the real
     *  reserve check above (a withdrawal can be under-reserved AND under-approved at once; those
     *  are checked in priority order in updateWarningState, since only the reserve one is truly
     *  unresolvable). */
    private var wusdtRemainingAllowance: BigDecimal = BigDecimal.ZERO
    private var polkadotUsdtRemainingAllowance: BigDecimal = BigDecimal.ZERO

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
            _consentChecked.value = false
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
            _consentChecked.value = false
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
            _consentChecked.value = false
            updateUI()
            calculateOutput()
            updateWarningState()
            updateCards()
        }
    }

    fun setAmount(amount: Double) {
        currentAmount = amount
        // A consent already given was for whatever amount was entered at the time - changing the
        // amount means re-confirming, not silently carrying an old opt-in over to a new one.
        _consentChecked.value = false
        calculateOutput()
        updateInsufficientBalanceState()
        updateWarningState() // re-check the entered amount against the cached reserve/allowance
    }

    fun maxClicked() {
        _fillAmountEvent.value = Event(availableBalance.stripTrailingZeros().toPlainString())
    }

    /** Called every time this screen becomes visible again, including returning from a completed
     *  execution - always starts the next operation from a clean slate rather than leaving the
     *  just-spent amount sitting in the input (the entire class of bug that used to require a
     *  hand-timed field-clear right after submit no longer exists once input and execution are
     *  different screens: there is no "just submitted" moment on this screen anymore). */
    fun resetAmount() {
        currentAmount = 0.0
        _consentChecked.value = false
        _fillAmountEvent.value = Event("")
        calculateOutput()
        updateInsufficientBalanceState()
        updateWarningState()
    }

    /** The actual confirmation gate: re-checks amount against the latest known balance/reserve
     *  right before committing to a real on-chain transfer, rather than trusting the Swap
     *  button's enabled look alone (a UI affordance, not a domain guarantee - see
     *  BridgeExecutionViewModel's doc for why the previous single-screen design needed a
     *  best-effort in-flight flag here instead of a real gate). */
    fun swapClicked() {
        val dir = _direction.value ?: return
        if (currentAmount <= 0) return

        val requested = BigDecimal.valueOf(currentAmount)
        if (requested > availableBalance) {
            updateInsufficientBalanceState()
            return
        }
        if (dir == BridgeDirection.WUSDT_TO_USDT && requested > polkadotUsdtReserve) {
            updateWarningState()
            return
        }

        val expectManualReview = exceedsAutoPayBounds(dir, requested)
        if (expectManualReview && _consentChecked.value != true) {
            updateWarningState()
            return
        }

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

        val amount = currentAmount

        launch {
            val chain = chainRegistry.getChain(chainId)
            val accountId = BRIDGE_ADDRESS_GENERIC.toAccountId()
            val bridgeAddress = chain.addressOf(accountId)

            router.openBridgeExecution(
                BridgeExecutionPayload(
                    originChainId = chainId,
                    originAssetId = assetId,
                    bridgeAddress = bridgeAddress,
                    amount = amount,
                    destChainId = destChainId,
                    destAssetId = destAssetId,
                    expectManualReview = expectManualReview,
                )
            )
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
            wusdtRemainingAllowance = try {
                BigDecimal(bridgeMultisigInteractor.getWusdtRemainingAllowance()).divide(USDT_DECIMALS_DIVISOR)
            } catch (e: Exception) {
                BigDecimal.ZERO
            }
            polkadotUsdtRemainingAllowance = try {
                BigDecimal(bridgeMultisigInteractor.getPolkadotUsdtRemainingAllowance()).divide(USDT_DECIMALS_DIVISOR)
            } catch (e: Exception) {
                BigDecimal.ZERO
            }
            updateWarningState()
        }
    }

    /** Two independent, differently-resolvable "no" conditions, checked in priority order - never
     *  collapsed into one generic warning:
     *  1. Real reserve exceeded (withdrawal direction only) - the bridge doesn't hold enough real
     *     USDT on Polkadot Asset Hub. No signature can fix this; a hard block.
     *  2. Automation-key approval exceeded (either direction) - funds exist, the automation key
     *     just isn't currently approved to move that much without 3-of-5 review. Resolvable, so
     *     this is an opt-in consent gate (see consentRequired/consentChecked), not a hard block.
     *
     *  Consent is also required above MAX_SINGLE_TX regardless of the remaining allowance - a
     *  fresh renewal tops the allowance up to 200,000 but usdt-bridge's own hard per-tx cap
     *  (50,000) still forces manual review for anything above it, so checking only the allowance
     *  would wrongly predict "fast" for a large single swap. */
    private fun updateWarningState() {
        val dir = _direction.value ?: return
        val requested = BigDecimal.valueOf(currentAmount)

        val reserveExceeded = dir == BridgeDirection.WUSDT_TO_USDT && currentAmount > 0 && requested > polkadotUsdtReserve

        if (reserveExceeded) {
            _consentRequired.postValue(false)
            _showWarning.postValue(true)
            _warningBlocked.postValue(true)
            _warningText.postValue(
                resourceManager.getString(
                    R.string.bridge_wusdt_to_usdt_blocked,
                    polkadotUsdtReserve.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
                )
            )
            updateButtonState()
            return
        }

        val needsConsent = currentAmount > 0 && exceedsAutoPayBounds(dir, requested)

        _consentRequired.postValue(needsConsent)
        if (needsConsent) {
            _showWarning.postValue(true)
            _warningBlocked.postValue(false)
            _warningText.postValue(resourceManager.getString(R.string.bridge_consent_required_message))
        } else {
            _showWarning.postValue(false)
            _warningText.postValue("")
        }

        updateButtonState()
    }

    /** Shared by updateWarningState/updateButtonState/swapClicked so all three ever agree on the
     *  same prediction - see updateWarningState's doc comment for why both the on-chain allowance
     *  AND the hard per-tx cap need checking, not just the allowance. */
    private fun exceedsAutoPayBounds(dir: BridgeDirection, requested: BigDecimal): Boolean {
        val relevantAllowance = when (dir) {
            BridgeDirection.USDT_TO_WUSDT -> wusdtRemainingAllowance
            BridgeDirection.WUSDT_TO_USDT -> polkadotUsdtRemainingAllowance
        }
        val maxSingleTx = BigDecimal.valueOf(BridgeMultisigConstants.MAX_SINGLE_TX).divide(USDT_DECIMALS_DIVISOR)
        return requested > relevantAllowance || requested > maxSingleTx
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
        val reserveExceeded = dir == BridgeDirection.WUSDT_TO_USDT && requested > polkadotUsdtReserve
        val consentSatisfied = !exceedsAutoPayBounds(dir, requested) || _consentChecked.value == true

        _buttonState.value = when {
            currentAmount <= 0 -> ButtonState.DISABLED
            currentAmount < MIN_USDT -> ButtonState.DISABLED
            requested > availableBalance -> ButtonState.DISABLED
            reserveExceeded -> ButtonState.DISABLED
            !consentSatisfied -> ButtonState.DISABLED
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
            applySignerState(state, _signButtonVisible, _signButtonRed, _signButtonEnabled, _signButtonLabel)
        }
        launch {
            val state = bridgeMultisigInteractor.getPolkadotSignerState()
            applySignerState(state, _polkadotSignButtonVisible, _polkadotSignButtonRed, _polkadotSignButtonEnabled, _polkadotSignButtonLabel)
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

    fun polkadotSignClicked() {
        if (_polkadotSignButtonEnabled.value != true) return

        _polkadotSignButtonEnabled.postValue(false)
        _polkadotSignButtonLabel.postValue(resourceManager.getString(R.string.bridge_sign_in_progress))

        launch {
            bridgeMultisigInteractor.submitPolkadotRenewalSignature()
                .onFailure {
                    _polkadotSignButtonLabel.postValue(resourceManager.getString(R.string.bridge_sign_error))
                }
            refreshSignerState()
        }
    }

    private fun applySignerState(
        state: BridgeSignerState?,
        visible: MutableLiveData<Boolean>,
        red: MutableLiveData<Boolean>,
        enabled: MutableLiveData<Boolean>,
        label: MutableLiveData<String>,
    ) {
        if (state == null) {
            visible.postValue(false)
            return
        }

        visible.postValue(true)

        when {
            !state.needsRenewal -> {
                red.postValue(false)
                enabled.postValue(false)
                label.postValue(resourceManager.getString(R.string.bridge_sign_status_ok, state.signatoryRole))
            }
            state.alreadySignedPendingRenewal -> {
                red.postValue(true)
                enabled.postValue(false)
                label.postValue(resourceManager.getString(R.string.bridge_sign_waiting_others))
            }
            else -> {
                red.postValue(true)
                enabled.postValue(true)
                label.postValue(resourceManager.getString(R.string.bridge_sign_button, state.signatoryRole))
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
