package io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.transaction

import io.novafoundation.nova.common.utils.BitcoinInput
import io.novafoundation.nova.common.utils.BitcoinOutput
import io.novafoundation.nova.common.utils.BitcoinTransaction
import io.novafoundation.nova.common.utils.DerSignature
import io.novafoundation.nova.common.utils.decodeBitcoinDestination
import io.novafoundation.nova.common.utils.toBitcoinAddress
import io.novafoundation.nova.common.utils.toEcdsaSignatureData
import io.novafoundation.nova.common.utils.toP2wpkhScriptPubKey
import io.novafoundation.nova.common.utils.toScriptPubKey
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.extrinsic.SubmissionOrigin
import io.novafoundation.nova.feature_account_api.data.model.BitcoinFee
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_account_api.data.signer.CallExecutionType
import io.novafoundation.nova.feature_account_api.data.signer.SignerProvider
import io.novafoundation.nova.feature_account_api.data.signer.SubmissionHierarchy
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_account_api.domain.interfaces.requireMetaAccountFor
import io.novafoundation.nova.feature_account_api.domain.model.requireAccountIdIn
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.BitcoinApi
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.BitcoinUtxo
import io.novafoundation.nova.runtime.ext.commissionAsset
import io.novafoundation.nova.runtime.ext.requireMempoolSpaceBaseUrl
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import java.math.BigInteger

/** mempool.space's own dust threshold for a P2WPKH output - see `RealBitcoinTransactionService`'s doc for how this is used. */
private const val DUST_LIMIT_SAT = 546L

private const val TX_VERSION = 2
private const val LOCKTIME = 0

private data class UtxoSelection(
    val selectedUtxos: List<BitcoinUtxo>,
    val changeSat: Long,
    val feeSat: Long,
)

/**
 * Builds, signs and broadcasts Bitcoin transactions from this wallet's own native SegWit (P2WPKH) address - the
 * recipient output, however, can be P2WPKH, P2SH or P2PKH (see `BitcoinDestinationAddress.kt`; a real exchange
 * withdrawal address was confirmed live to be P2SH-only). UTXO selection and raw construction happen entirely
 * client-side (no server-assisted "createtransaction" the way TronGrid offers - mempool.space only exposes
 * UTXOs/fee-rate/broadcast, not transaction construction), using the hand-rolled protocol primitives in
 * [BitcoinTransaction]/[DerSignature]/`BitcoinAddress.kt` verified against BIP143/BIP173.
 *
 * ## UTXO selection
 * Greedy largest-first over CONFIRMED UTXOs only (unconfirmed outputs are skipped - spending them risks the
 * whole transaction unraveling if the parent is replaced/dropped), matching the exchange's proven approach.
 * If the leftover after amount+fee would be below Bitcoin's dust threshold ([DUST_LIMIT_SAT]), no change output
 * is created and the leftover is folded into the fee instead of producing an uneconomical-to-spend output.
 *
 * ## Fee
 * `feeRate (sat/vB, from mempool.space's ~30-minute estimate) * estimated vsize` (the same
 * `inputs*68 + outputs*31 + 11` heuristic already proven in production by `pezkuwi-exchange/wallet-service`).
 *
 * ## Signing
 * Each input gets its own BIP143 sighash (unlike Tron/Ethereum's single whole-transaction hash) - computed by
 * [BitcoinTransaction.bip143Sighash], signed via the same [io.novafoundation.nova.feature_account_api.data.signer.NovaSigner.signRaw]
 * primitive Tron/Ethereum already use (`skipMessageHashing = true`, since the sighash is already the final
 * digest to sign), then DER-encoded with low-S normalization via [DerSignature] - Bitcoin's one departure from
 * Tron/Ethereum's fixed compact r+s+v format. No new signing call path was added.
 *
 * ## Broadcast
 * `POST /tx` with the fully serialized signed transaction, hex-encoded, as a raw text body.
 */
class RealBitcoinTransactionService(
    private val accountRepository: AccountRepository,
    private val signerProvider: SignerProvider,
    private val bitcoinApi: BitcoinApi,
) : BitcoinTransactionService {

    override suspend fun calculateFee(chain: Chain, origin: TransactionOrigin, recipientAddress: String, amountSat: BigInteger): Fee {
        val submittingMetaAccount = accountRepository.requireMetaAccountFor(origin, chain.id)
        val ownerAccountId = submittingMetaAccount.requireAccountIdIn(chain)
        val baseUrl = chain.requireMempoolSpaceBaseUrl()

        val utxos = confirmedUtxos(baseUrl, ownerAccountId)
        val feeRate = bitcoinApi.fetchRecommendedFeeRateSatPerVbyte(baseUrl)
        val selection = selectUtxos(utxos, amountSat.toLong().coerceAtLeast(0), feeRate)

        val feeSat = selection?.feeSat ?: 0L

        return BitcoinFee(feeSat.toBigInteger(), SubmissionOrigin.singleOrigin(ownerAccountId), chain.commissionAsset)
    }

    override suspend fun transact(
        chain: Chain,
        origin: TransactionOrigin,
        recipientAddress: String,
        presetFee: Fee?,
        amountSat: BigInteger
    ): Result<ExtrinsicSubmission> = runCatching {
        val submittingMetaAccount = accountRepository.requireMetaAccountFor(origin, chain.id)
        val ownerAccountId = submittingMetaAccount.requireAccountIdIn(chain)
        val ownerPublicKey = requireNotNull(submittingMetaAccount.bitcoinPublicKey) {
            "No bitcoin public key found for meta account ${submittingMetaAccount.id}"
        }
        val baseUrl = chain.requireMempoolSpaceBaseUrl()
        val amountSatLong = amountSat.toLong()
        val recipientScriptPubKey = recipientAddress.decodeBitcoinDestination().toScriptPubKey()

        val utxos = confirmedUtxos(baseUrl, ownerAccountId)
        val feeRate = bitcoinApi.fetchRecommendedFeeRateSatPerVbyte(baseUrl)
        val selection = selectUtxos(utxos, amountSatLong, feeRate)
            ?: error("Insufficient confirmed UTXOs to cover amount + fee")

        val inputs = selection.selectedUtxos.map { utxo ->
            BitcoinInput(txid = utxo.txid.fromHex(), vout = utxo.vout, valueSat = utxo.valueSat)
        }

        val outputs = buildList {
            add(BitcoinOutput(valueSat = amountSatLong, scriptPubKey = recipientScriptPubKey))
            if (selection.changeSat > 0) {
                add(BitcoinOutput(valueSat = selection.changeSat, scriptPubKey = ownerAccountId.toP2wpkhScriptPubKey()))
            }
        }

        val signer = signerProvider.rootSignerFor(submittingMetaAccount)

        val witnesses = inputs.indices.map { index ->
            val sighash = BitcoinTransaction.bip143Sighash(TX_VERSION, inputs, outputs, index, ownerAccountId, LOCKTIME)
            val signedRaw = signer.signRaw(SignerPayloadRaw(message = sighash, accountId = ownerAccountId, skipMessageHashing = true))
            val signature = signedRaw.toEcdsaSignatureData()

            DerSignature.encode(signature.r, signature.s) to ownerPublicKey
        }

        val signedTxBytes = BitcoinTransaction.serializeSigned(TX_VERSION, inputs, outputs, witnesses, LOCKTIME)
        val txid = bitcoinApi.broadcastTransaction(baseUrl, signedTxBytes.toHexString(withPrefix = false))

        ExtrinsicSubmission(
            hash = txid,
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
        amountSat: BigInteger
    ): Result<TransactionExecution> {
        // Broadcast acceptance is already a strong signal (same posture as Tron/EVM) - this sits outside the
        // primary send flow's critical path, which only ever calls transact().
        return transact(chain, origin, recipientAddress, presetFee, amountSat).map { TransactionExecution.Bitcoin(it.hash) }
    }

    private suspend fun confirmedUtxos(baseUrl: String, ownerAccountId: AccountId): List<BitcoinUtxo> {
        val address = ownerAccountId.toBitcoinAddress()

        return bitcoinApi.fetchUtxos(baseUrl, address).filter { it.confirmed }
    }

    private fun selectUtxos(utxos: List<BitcoinUtxo>, amountSat: Long, feeRateSatPerVbyte: Long): UtxoSelection? {
        val sorted = utxos.sortedByDescending { it.valueSat }
        val selected = mutableListOf<BitcoinUtxo>()
        var total = 0L

        for (utxo in sorted) {
            selected += utxo
            total += utxo.valueSat

            val vsizeWithChange = BitcoinTransaction.estimateVsize(selected.size, outputCount = 2)
            val feeWithChange = feeRateSatPerVbyte * vsizeWithChange
            if (total < amountSat + feeWithChange) continue

            val changeSat = total - amountSat - feeWithChange

            return if (changeSat >= DUST_LIMIT_SAT) {
                UtxoSelection(selected.toList(), changeSat = changeSat, feeSat = feeWithChange)
            } else {
                // Folding a sub-dust leftover into the fee instead of creating an uneconomical-to-spend output.
                UtxoSelection(selected.toList(), changeSat = 0, feeSat = total - amountSat)
            }
        }

        return null
    }
}
