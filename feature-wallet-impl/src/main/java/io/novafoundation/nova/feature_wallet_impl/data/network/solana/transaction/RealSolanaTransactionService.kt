package io.novafoundation.nova.feature_wallet_impl.data.network.solana.transaction

import io.novafoundation.nova.common.utils.SolanaTransaction
import io.novafoundation.nova.common.utils.emptySolanaAccountId
import io.novafoundation.nova.common.utils.isValidSolanaAddress
import io.novafoundation.nova.common.utils.solanaAddressToAccountId
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.extrinsic.SubmissionOrigin
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_account_api.data.model.SolanaFee
import io.novafoundation.nova.feature_account_api.data.signer.CallExecutionType
import io.novafoundation.nova.feature_account_api.data.signer.SignerProvider
import io.novafoundation.nova.feature_account_api.data.signer.SubmissionHierarchy
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_account_api.domain.interfaces.requireMetaAccountFor
import io.novafoundation.nova.feature_account_api.domain.model.requireAccountIdIn
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.SolanaApi
import io.novafoundation.nova.runtime.ext.commissionAsset
import io.novafoundation.nova.runtime.ext.requireSolanaRpcBaseUrl
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import java.math.BigInteger

/**
 * Builds, signs and broadcasts a Solana native SOL transfer from this wallet's own Ed25519 account - see
 * `common/utils/SolanaTransaction.kt` for the wire-format primitives (cross-validated against the `solders`
 * Python library) and `SecretsSigner`/`RealSecretsMetaAccount.multiChainEncryptionIn` for how a Solana account
 * gets routed to `Signer.signEd25519` (raw-message Ed25519, no external hashing - matches Solana's own
 * signing model exactly, unlike Bitcoin/Ethereum's hash-then-sign schemes).
 *
 * Unlike Bitcoin (UTXO selection) or Tron (server-assisted transaction construction), Solana's account model
 * needs only two network round-trips beyond fee/broadcast themselves: `getLatestBlockhash` (a transaction is
 * only valid against a recent one, ~60-90s window) and `getFeeForMessage` (the network's authoritative,
 * flat-rate-per-signature fee for this exact message - not a client-side estimate).
 */
class RealSolanaTransactionService(
    private val accountRepository: AccountRepository,
    private val signerProvider: SignerProvider,
    private val solanaApi: SolanaApi,
) : SolanaTransactionService {

    override suspend fun calculateFee(chain: Chain, origin: TransactionOrigin, recipientAddress: String, amountLamports: BigInteger): Fee {
        val submittingMetaAccount = accountRepository.requireMetaAccountFor(origin, chain.id)
        val ownerAccountId = submittingMetaAccount.requireAccountIdIn(chain)
        val baseUrl = chain.requireSolanaRpcBaseUrl()

        val recentBlockhash = solanaApi.fetchLatestBlockhash(baseUrl)

        // Solana's fee is a flat rate per required signature (always 1 here) - it does not depend on the
        // recipient or amount, so a syntactically-valid placeholder recipient is fine when the real one can't
        // be parsed yet (e.g. mid-typing in the send UI), same tolerance Bitcoin's calculateFee has.
        val recipientAccountId = recipientAddress.takeIf { it.isValidSolanaAddress() }
            ?.solanaAddressToAccountId()
            ?: emptySolanaAccountId()

        val message = SolanaTransaction.buildTransferMessage(ownerAccountId, recipientAccountId, lamports = 0L, recentBlockhash)
        val feeLamports = solanaApi.calculateFeeForMessage(baseUrl, message)

        return SolanaFee(feeLamports, SubmissionOrigin.singleOrigin(ownerAccountId), chain.commissionAsset)
    }

    override suspend fun transact(
        chain: Chain,
        origin: TransactionOrigin,
        recipientAddress: String,
        presetFee: Fee?,
        amountLamports: BigInteger
    ): Result<ExtrinsicSubmission> = runCatching {
        val submittingMetaAccount = accountRepository.requireMetaAccountFor(origin, chain.id)
        val ownerAccountId = submittingMetaAccount.requireAccountIdIn(chain)
        val baseUrl = chain.requireSolanaRpcBaseUrl()

        val recipientAccountId = recipientAddress.solanaAddressToAccountId()
        val recentBlockhash = solanaApi.fetchLatestBlockhash(baseUrl)

        val message = SolanaTransaction.buildTransferMessage(ownerAccountId, recipientAccountId, amountLamports.toLong(), recentBlockhash)

        val signer = signerProvider.rootSignerFor(submittingMetaAccount)
        val signedRaw = signer.signRaw(SignerPayloadRaw(message = message, accountId = ownerAccountId, skipMessageHashing = true))
        val signature = signedRaw.signatureWrapper.signature

        val signedTransaction = SolanaTransaction.serializeSigned(message, signature)
        val txSignature = solanaApi.broadcastTransaction(baseUrl, signedTransaction)

        ExtrinsicSubmission(
            hash = txSignature,
            submissionOrigin = SubmissionOrigin.singleOrigin(ownerAccountId),
            callExecutionType = CallExecutionType.IMMEDIATE,
            submissionHierarchy = SubmissionHierarchy(submittingMetaAccount, CallExecutionType.IMMEDIATE)
        )
    }

    override suspend fun transactAndAwaitExecution(
        chain: Chain,
        origin: TransactionOrigin,
        recipientAddress: String,
        presetFee: Fee?,
        amountLamports: BigInteger
    ): Result<TransactionExecution> {
        // Broadcast acceptance is already a strong signal (same posture as Bitcoin/Tron/EVM) - this sits
        // outside the primary send flow's critical path, which only ever calls transact().
        return transact(chain, origin, recipientAddress, presetFee, amountLamports).map { TransactionExecution.Solana(it.hash) }
    }
}
