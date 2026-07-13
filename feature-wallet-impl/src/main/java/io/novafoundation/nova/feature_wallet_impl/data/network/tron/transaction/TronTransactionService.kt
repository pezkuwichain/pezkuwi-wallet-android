package io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction

import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.AccountId
import java.math.BigInteger

/**
 * What kind of Tron transaction to build. Both cases ultimately burn TRX for bandwidth/energy per Tron's default
 * protocol behavior - this service never stakes/rents Energy or Bandwidth, it only estimates the automatic burn
 * and lets the caller (asset transfer validation) block the send if the user's TRX balance can't cover it.
 */
sealed class TronTransactionIntent {

    class Native(val amountSun: BigInteger) : TronTransactionIntent()

    /** @param contractAddress Base58Check TRC-20 contract address, as stored in chain config (`Type.Trc20.contractAddress`). */
    class Trc20Transfer(val contractAddress: String, val amountSun: BigInteger) : TronTransactionIntent()
}

/**
 * Mirrors [io.novafoundation.nova.feature_account_api.data.ethereum.transaction.EvmTransactionService]'s shape
 * (calculateFee/transact/transactAndAwaitExecution over a sending origin), but for Tron. Unlike the EVM service,
 * this lives entirely in `feature-wallet-impl` rather than being split across `feature-account-api`/`-impl`,
 * since (for now, Phase 2 send-only scope) it is only ever consumed by `TronNativeAssetTransfers`/
 * `Trc20AssetTransfers` in this module, and it needs [io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi],
 * which itself lives in this module (feature-account-impl cannot depend on feature-wallet-impl).
 *
 * Construction goes through TronGrid's own `/wallet/createtransaction` and `/wallet/triggersmartcontract`
 * endpoints rather than hand-rolled protobuf encoding - see `RealTronTransactionService` for details and the
 * live-testnet verification notes.
 */
interface TronTransactionService {

    suspend fun calculateFee(chain: Chain, origin: TransactionOrigin, recipient: AccountId, intent: TronTransactionIntent): Fee

    suspend fun transact(
        chain: Chain,
        origin: TransactionOrigin,
        recipient: AccountId,
        presetFee: Fee?,
        intent: TronTransactionIntent
    ): Result<ExtrinsicSubmission>

    suspend fun transactAndAwaitExecution(
        chain: Chain,
        origin: TransactionOrigin,
        recipient: AccountId,
        presetFee: Fee?,
        intent: TronTransactionIntent
    ): Result<TransactionExecution>
}
