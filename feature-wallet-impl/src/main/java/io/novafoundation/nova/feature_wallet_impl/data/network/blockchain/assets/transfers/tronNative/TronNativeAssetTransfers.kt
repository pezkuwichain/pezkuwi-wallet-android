package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.tronNative

import io.novafoundation.nova.common.validation.ValidationSystem
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.intoOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.AssetSourceRegistry
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.AssetTransfer
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.AssetTransfers
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.WeightedAssetTransfer
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.amountInPlanks
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.model.TransferParsedFromCall
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.validations.checkForFeeChanges
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.validations.positiveAmount
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.validations.recipientIsNotSystemAccount
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.validations.sufficientBalanceInUsedAsset
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.validations.sufficientTransferableBalanceToPayOriginFee
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.validations.validAddress
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.TronTransactionIntent
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.TronTransactionService
import io.novafoundation.nova.feature_wallet_impl.domain.validaiton.recipientCanAcceptTransfer
import io.novafoundation.nova.runtime.ext.accountIdOrDefault
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import kotlinx.coroutines.CoroutineScope

/**
 * Native TRX transfer. No Energy/Bandwidth staking or rental is implemented (out of scope for this send-only
 * phase) - Tron's default protocol behavior (automatically burning TRX when free Bandwidth is insufficient) is
 * all that's needed; [TronTransactionService] estimates that burn and reports it as [Fee], and the generic
 * [sufficientTransferableBalanceToPayOriginFee] validation (same one EVM native/ERC-20 transfers already reuse)
 * blocks the send if the TRX balance can't cover it.
 */
class TronNativeAssetTransfers(
    private val tronTransactionService: TronTransactionService,
    private val assetSourceRegistry: AssetSourceRegistry,
) : AssetTransfers {

    override fun getValidationSystem(coroutineScope: CoroutineScope) = ValidationSystem {
        validAddress()
        recipientIsNotSystemAccount()

        positiveAmount()

        sufficientBalanceInUsedAsset()
        sufficientTransferableBalanceToPayOriginFee()

        recipientCanAcceptTransfer(assetSourceRegistry)

        checkForFeeChanges(assetSourceRegistry, coroutineScope)
    }

    override suspend fun calculateFee(transfer: AssetTransfer, coroutineScope: CoroutineScope): Fee {
        return tronTransactionService.calculateFee(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipient = transfer.originChain.accountIdOrDefault(transfer.recipient),
            intent = TronTransactionIntent.Native(transfer.amountInPlanks)
        )
    }

    override suspend fun performTransfer(transfer: WeightedAssetTransfer, coroutineScope: CoroutineScope): Result<ExtrinsicSubmission> {
        return tronTransactionService.transact(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipient = transfer.originChain.accountIdOrDefault(transfer.recipient),
            presetFee = transfer.fee.submissionFee,
            intent = TronTransactionIntent.Native(transfer.amountInPlanks)
        )
    }

    override suspend fun performTransferAndAwaitExecution(transfer: WeightedAssetTransfer, coroutineScope: CoroutineScope): Result<TransactionExecution> {
        return tronTransactionService.transactAndAwaitExecution(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipient = transfer.originChain.accountIdOrDefault(transfer.recipient),
            presetFee = transfer.fee.submissionFee,
            intent = TronTransactionIntent.Native(transfer.amountInPlanks)
        )
    }

    override suspend fun areTransfersEnabled(chainAsset: Chain.Asset): Boolean {
        return true
    }

    override suspend fun parseTransfer(call: GenericCall.Instance, chain: Chain): TransferParsedFromCall? {
        return null
    }
}
