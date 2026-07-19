package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.transaction

import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import java.math.BigInteger

/**
 * Mirrors [io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.TronTransactionService]'s
 * shape (calculateFee/transact/transactAndAwaitExecution over a sending origin), but for Bitcoin. See
 * `RealBitcoinTransactionService` for the UTXO-model construction/signing details, which differ substantially
 * from Tron's account-model approach.
 *
 * [recipientAddress] is the raw destination address string, not an [io.novasama.substrate_sdk_android.runtime.AccountId] -
 * unlike every other chain's transfer path, a Bitcoin destination can legitimately be a P2SH/P2PKH address (a
 * real exchange withdrawal address was confirmed to be P2SH-only, with no way to request native SegWit instead),
 * which needs its own distinct scriptPubKey shape. Converting to a generic 20-byte AccountId this early would
 * discard which shape it needs to be - see `BitcoinDestinationAddress.kt`.
 */
interface BitcoinTransactionService {

    suspend fun calculateFee(chain: Chain, origin: TransactionOrigin, recipientAddress: String, amountSat: BigInteger): Fee

    suspend fun transact(
        chain: Chain,
        origin: TransactionOrigin,
        recipientAddress: String,
        presetFee: Fee?,
        amountSat: BigInteger
    ): Result<ExtrinsicSubmission>

    suspend fun transactAndAwaitExecution(
        chain: Chain,
        origin: TransactionOrigin,
        recipientAddress: String,
        presetFee: Fee?,
        amountSat: BigInteger
    ): Result<TransactionExecution>
}
