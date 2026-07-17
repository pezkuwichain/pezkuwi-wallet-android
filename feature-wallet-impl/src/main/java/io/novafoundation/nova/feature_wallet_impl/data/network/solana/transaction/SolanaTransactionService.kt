package io.novafoundation.nova.feature_wallet_impl.data.network.solana.transaction

import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import java.math.BigInteger

/**
 * Mirrors [io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.transaction.BitcoinTransactionService]'s
 * shape (calculateFee/transact/transactAndAwaitExecution over a sending origin), but for Solana's simpler
 * account-model native transfer - see `RealSolanaTransactionService` for the message-build/sign/broadcast detail.
 */
interface SolanaTransactionService {

    suspend fun calculateFee(chain: Chain, origin: TransactionOrigin, recipientAddress: String, amountLamports: BigInteger): Fee

    suspend fun transact(
        chain: Chain,
        origin: TransactionOrigin,
        recipientAddress: String,
        presetFee: Fee?,
        amountLamports: BigInteger
    ): Result<ExtrinsicSubmission>

    suspend fun transactAndAwaitExecution(
        chain: Chain,
        origin: TransactionOrigin,
        recipientAddress: String,
        presetFee: Fee?,
        amountLamports: BigInteger
    ): Result<TransactionExecution>
}
