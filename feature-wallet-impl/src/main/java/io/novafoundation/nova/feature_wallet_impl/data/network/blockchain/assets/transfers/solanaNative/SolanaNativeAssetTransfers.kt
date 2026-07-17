package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.solanaNative

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
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.transaction.SolanaTransactionService
import io.novafoundation.nova.feature_wallet_impl.domain.validaiton.recipientCanAcceptTransfer
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import kotlinx.coroutines.CoroutineScope

/**
 * Native SOL transfer. Mirrors
 * [io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.bitcoinNative.BitcoinNativeAssetTransfers]'s
 * exact shape - see `RealSolanaTransactionService` for the message-build/sign/broadcast detail.
 */
class SolanaNativeAssetTransfers(
    private val solanaTransactionService: SolanaTransactionService,
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
        return solanaTransactionService.calculateFee(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipientAddress = transfer.recipient,
            amountLamports = transfer.amountInPlanks
        )
    }

    override suspend fun performTransfer(transfer: WeightedAssetTransfer, coroutineScope: CoroutineScope): Result<ExtrinsicSubmission> {
        return solanaTransactionService.transact(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipientAddress = transfer.recipient,
            presetFee = transfer.fee.submissionFee,
            amountLamports = transfer.amountInPlanks
        )
    }

    override suspend fun performTransferAndAwaitExecution(transfer: WeightedAssetTransfer, coroutineScope: CoroutineScope): Result<TransactionExecution> {
        return solanaTransactionService.transactAndAwaitExecution(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipientAddress = transfer.recipient,
            presetFee = transfer.fee.submissionFee,
            amountLamports = transfer.amountInPlanks
        )
    }

    override suspend fun areTransfersEnabled(chainAsset: Chain.Asset): Boolean {
        return true
    }

    override suspend fun parseTransfer(call: GenericCall.Instance, chain: Chain): TransferParsedFromCall? {
        return null
    }
}
