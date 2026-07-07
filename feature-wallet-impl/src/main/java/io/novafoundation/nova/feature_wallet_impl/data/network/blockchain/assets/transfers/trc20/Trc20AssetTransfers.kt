package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.trc20

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
import io.novafoundation.nova.runtime.ext.requireTrc20
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import kotlinx.coroutines.CoroutineScope

/**
 * TRC-20 token transfer (e.g. USDT-TRC20). The fee is always denominated in native TRX, never in the TRC-20
 * token being sent - same pattern as an ERC-20 transfer's fee being paid in ETH, not the ERC-20 token (compare
 * [io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.evmErc20.EvmErc20AssetTransfers]).
 * This falls out for free from [TronTransactionService.calculateFee] always returning a [io.novafoundation.nova.feature_account_api.data.model.TronFee]
 * denominated in `chain.commissionAsset` (native TRX), combined with the fully-generic
 * [sufficientTransferableBalanceToPayOriginFee] validation checking that commission asset's balance regardless
 * of which asset is actually being transferred.
 */
class Trc20AssetTransfers(
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
            intent = transfer.intoTrc20Intent()
        )
    }

    override suspend fun performTransfer(transfer: WeightedAssetTransfer, coroutineScope: CoroutineScope): Result<ExtrinsicSubmission> {
        return tronTransactionService.transact(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipient = transfer.originChain.accountIdOrDefault(transfer.recipient),
            presetFee = transfer.fee.submissionFee,
            intent = transfer.intoTrc20Intent()
        )
    }

    override suspend fun performTransferAndAwaitExecution(transfer: WeightedAssetTransfer, coroutineScope: CoroutineScope): Result<TransactionExecution> {
        return tronTransactionService.transactAndAwaitExecution(
            chain = transfer.originChain,
            origin = transfer.sender.intoOrigin(),
            recipient = transfer.originChain.accountIdOrDefault(transfer.recipient),
            presetFee = transfer.fee.submissionFee,
            intent = transfer.intoTrc20Intent()
        )
    }

    override suspend fun areTransfersEnabled(chainAsset: Chain.Asset): Boolean {
        return true
    }

    override suspend fun parseTransfer(call: GenericCall.Instance, chain: Chain): TransferParsedFromCall? {
        return null
    }

    private fun AssetTransfer.intoTrc20Intent(): TronTransactionIntent.Trc20Transfer {
        val trc20 = originChainAsset.requireTrc20()

        return TronTransactionIntent.Trc20Transfer(trc20.contractAddress, amountInPlanks)
    }
}
