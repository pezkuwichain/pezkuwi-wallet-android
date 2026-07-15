package io.novafoundation.nova.feature_assets.presentation.bridge.execution

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.novafoundation.nova.common.base.BaseViewModel
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.view.ExecutionTimerView
import io.novafoundation.nova.feature_account_api.data.fee.FeePaymentCurrency
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.domain.WalletInteractor
import io.novafoundation.nova.feature_assets.domain.send.SendInteractor
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.WeightedAssetTransfer
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.buildAssetTransfer
import io.novafoundation.nova.feature_wallet_api.domain.SendUseCase
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.ChainWithAsset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * Dedicated execution screen for a bridge transfer - deliberately has no amount input and no live
 * balance-vs-amount validation, structurally the same way SwapExecutionViewModel has none: that
 * code simply doesn't exist here, so it can't race against a live balance update the way it did
 * when submit+wait lived on the same screen/ViewModel as the amount field (see
 * BridgeViewModel.swapClicked - the confirmation gate - for where amount/reserve validation now
 * happens instead, once, right before navigating here).
 */
class BridgeExecutionViewModel(
    private val payload: BridgeExecutionPayload,
    private val resourceManager: ResourceManager,
    private val chainRegistry: ChainRegistry,
    private val walletInteractor: WalletInteractor,
    private val sendInteractor: SendInteractor,
    private val sendUseCase: SendUseCase,
    private val selectedAccountUseCase: SelectedAccountUseCase,
    private val router: AssetsRouter,
) : BaseViewModel() {

    companion object {
        /** Cosmetic only - the real completion signal is the awaited dispatch result, which can
         *  arrive before or after this visually elapses (same as the swap screen's own timer). */
        val SUBMIT_WAIT_TIMEOUT = 30.seconds

        /** How long to actively watch the destination balance before admitting we can't confirm
         *  completion yet - not a claim about how long the bridge itself actually takes. */
        val DEPOSIT_WAIT_TIMEOUT = 90.seconds

        const val FEE_PERCENT = 0.001
    }

    private val _state = MutableLiveData<BridgeExecutionState>(BridgeExecutionState.SubmittingOrigin)
    val state: LiveData<BridgeExecutionState> = _state

    private val _label = MutableLiveData<String>()
    val label: LiveData<String> = _label

    private val _timerState = MutableLiveData<ExecutionTimerView.State?>(null)
    val timerState: LiveData<ExecutionTimerView.State?> = _timerState

    private val _doneButtonVisible = MutableLiveData(false)
    val doneButtonVisible: LiveData<Boolean> = _doneButtonVisible

    init {
        launch {
            submit()
        }
    }

    fun doneClicked() {
        router.back()
    }

    private suspend fun submit() {
        _state.postValue(BridgeExecutionState.SubmittingOrigin)
        _label.postValue(resourceManager.getString(R.string.bridge_execution_submitting_label))
        _timerState.postValue(ExecutionTimerView.State.CountdownTimer(SUBMIT_WAIT_TIMEOUT))

        val chain = chainRegistry.getChain(payload.originChainId)
        val chainAsset = chain.assetsById.getValue(payload.originAssetId)
        val chainWithAsset = ChainWithAsset(chain, chainAsset)
        val bigDecimalAmount = BigDecimal.valueOf(payload.amount)

        val submissionResult = runCatching {
            val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()

            val assetTransfer = buildAssetTransfer(
                metaAccount = metaAccount,
                feePaymentCurrency = FeePaymentCurrency.Native,
                origin = chainWithAsset,
                destination = chainWithAsset, // plain on-chain transfer, not a cross-chain route
                amount = bigDecimalAmount,
                transferringMaxAmount = false,
                address = payload.bridgeAddress,
            )

            val fee = sendInteractor.getFee(assetTransfer, viewModelScope)

            val weightedTransfer = WeightedAssetTransfer(
                sender = metaAccount,
                recipient = payload.bridgeAddress,
                originChain = chain,
                destinationChain = chain,
                destinationChainAsset = chainAsset,
                originChainAsset = chainAsset,
                amount = bigDecimalAmount,
                feePaymentCurrency = FeePaymentCurrency.Native,
                fee = fee.originFee,
                transferringMaxAmount = false,
            )

            sendUseCase.performOnChainTransferAndAwaitExecution(weightedTransfer, fee.originFee.submissionFee, viewModelScope)
                .getOrThrow()
        }

        submissionResult.fold(
            onSuccess = { observeDepositCompletion() },
            onFailure = { e ->
                _state.postValue(BridgeExecutionState.OriginFailed(e.message ?: resourceManager.getString(R.string.bridge_deposit_pending_message)))
                _timerState.postValue(ExecutionTimerView.State.Error)
                _doneButtonVisible.postValue(true)
            }
        )
    }

    /** Watches the DESTINATION balance for a real increase after the origin transfer already
     *  confirmed, rather than a cosmetic timer that reports "done" regardless of whether funds
     *  actually arrived - there is no completion callback for the bridge's own off-chain relay
     *  step, so balance-delta is the only honest signal available. Bounded to a reasonable wait;
     *  if it elapses without a confirmed increase, this says so plainly rather than implying
     *  success or failure it can't actually confirm - the swap may just need manual 3-of-5
     *  review, which can take longer. */
    private suspend fun observeDepositCompletion() {
        val balanceBefore = try {
            walletInteractor.assetFlow(payload.destChainId, payload.destAssetId).first().transferable
        } catch (e: Exception) {
            // Can't observe reliably - don't show a misleading progress state.
            _state.postValue(BridgeExecutionState.DestinationPendingReview)
            _timerState.postValue(null)
            _doneButtonVisible.postValue(true)
            return
        }

        val destChainName = chainRegistry.getChain(payload.destChainId).name

        _state.postValue(BridgeExecutionState.WaitingForDestination)
        _label.postValue(resourceManager.getString(R.string.bridge_execution_waiting_destination_label, destChainName))
        _timerState.postValue(ExecutionTimerView.State.CountdownTimer(DEPOSIT_WAIT_TIMEOUT))

        val minExpectedIncrease = BigDecimal.valueOf(payload.amount * (1 - FEE_PERCENT * 2)) // fee + rounding slack

        val confirmed = withTimeoutOrNull(DEPOSIT_WAIT_TIMEOUT.inWholeMilliseconds) {
            walletInteractor.assetFlow(payload.destChainId, payload.destAssetId)
                .first { it.transferable - balanceBefore >= minExpectedIncrease }
        } != null

        if (confirmed) {
            _state.postValue(BridgeExecutionState.DestinationConfirmed)
            _label.postValue(resourceManager.getString(R.string.bridge_deposit_success_label))
            _timerState.postValue(ExecutionTimerView.State.Success)
        } else {
            // Not a failure - just not confirmed within the wait window.
            _state.postValue(BridgeExecutionState.DestinationPendingReview)
            _timerState.postValue(null)
        }

        _doneButtonVisible.postValue(true)
    }
}
