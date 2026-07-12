package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.transaction

import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.AccountId
import java.math.BigInteger

/**
 * Mirrors [io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.TronTransactionService]'s
 * shape (calculateFee/transact/transactAndAwaitExecution over a sending origin), but for Bitcoin. See
 * `RealBitcoinTransactionService` for the UTXO-model construction/signing details, which differ substantially
 * from Tron's account-model approach.
 */
interface BitcoinTransactionService {

    suspend fun calculateFee(chain: Chain, origin: TransactionOrigin, recipient: AccountId, amountSat: BigInteger): Fee

    suspend fun transact(
        chain: Chain,
        origin: TransactionOrigin,
        recipient: AccountId,
        presetFee: Fee?,
        amountSat: BigInteger
    ): Result<ExtrinsicSubmission>

    suspend fun transactAndAwaitExecution(
        chain: Chain,
        origin: TransactionOrigin,
        recipient: AccountId,
        presetFee: Fee?,
        amountSat: BigInteger
    ): Result<TransactionExecution>
}
