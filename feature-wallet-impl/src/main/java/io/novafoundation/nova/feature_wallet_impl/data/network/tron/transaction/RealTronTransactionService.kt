package io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction

import io.novafoundation.nova.common.utils.castOrNull
import io.novafoundation.nova.common.utils.sha256
import io.novafoundation.nova.common.utils.toEcdsaSignatureData
import io.novafoundation.nova.common.utils.toTronHexAddress
import io.novafoundation.nova.common.utils.tronAddressToHexAddress
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_account_api.data.extrinsic.SubmissionOrigin
import io.novafoundation.nova.feature_account_api.data.model.Fee
import io.novafoundation.nova.feature_account_api.data.model.TronFee
import io.novafoundation.nova.feature_account_api.data.signer.CallExecutionType
import io.novafoundation.nova.feature_account_api.data.signer.SignerProvider
import io.novafoundation.nova.feature_account_api.data.signer.SubmissionHierarchy
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_account_api.domain.interfaces.requireMetaAccountFor
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.requireAccountIdIn
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.tranfers.TransactionExecution
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAccountResourceResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronUnsignedTransactionResponse
import io.novafoundation.nova.runtime.ext.commissionAsset
import io.novafoundation.nova.runtime.ext.requireTronGridBaseUrl
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import java.math.BigInteger

private const val ENERGY_FEE_PARAM_KEY = "getEnergyFee"
private const val TRANSACTION_FEE_PARAM_KEY = "getTransactionFee"

// Fallbacks only used if a live `getchainparameters` call fails or is missing the expected key - both values are
// what was observed live on Shasta testnet at implementation time, which also match Tron's long-standing mainnet
// defaults; the primary path always fetches live values.
private val FALLBACK_ENERGY_FEE_SUN = BigInteger.valueOf(420)
private val FALLBACK_BANDWIDTH_FEE_SUN = BigInteger.valueOf(1000)

// Used only if a triggerconstantcontract dry run fails outright (e.g. transient network error) and returns no
// energy_used at all - a conservative (intentionally high) stand-in for a simple TRC-20 transfer, which in
// practice costs on the order of 15-30k energy. Mirrors EvmErc20AssetTransfers' ERC_20_UPPER_GAS_LIMIT fallback.
private val FALLBACK_TRC20_ENERGY_UNITS = 65_000L
private val FALLBACK_TRC20_TX_SIZE_BYTES = 350L

// fee_limit sent with triggersmartcontract: Tron only ever burns what a call actually uses (up to this cap), so
// setting this generously above our own estimate does not cost the user more - it only avoids an OUT_OF_ENERGY
// failure if our estimate undershoots. Bounded above as a sanity guard against a runaway estimate.
private val MIN_FEE_LIMIT_SUN = BigInteger.valueOf(15_000_000) // 15 TRX
private val MAX_FEE_LIMIT_SUN = BigInteger.valueOf(100_000_000) // 100 TRX

private val EMPTY_RESOURCE = TronAccountResourceResponse()

/**
 * Builds, signs and broadcasts Tron transactions (native TRX and TRC-20) using TronGrid's own REST endpoints for
 * construction/broadcast, and this app's existing ECDSA signing primitive for signing - no Tron protobuf
 * (`Transaction`/`TransferContract`/`TriggerSmartContract`) encoding and no new crypto library were added.
 *
 * ## Construction
 * - Native TRX: `POST /wallet/createtransaction` with `{owner_address, to_address, amount}` (all hex-encoded,
 *   `visible: false`). Requires the owner account to already be activated on-chain (confirmed live: an
 *   unactivated owner gets `{"Error": "... no OwnerAccount."}`) - in practice this is never hit here, since a
 *   user can only reach the send flow with a positive TRX balance to send from, which itself implies the account
 *   was already activated by an earlier incoming transfer.
 * - TRC-20: `POST /wallet/triggersmartcontract` with the ABI-encoded `transfer(address,uint256)` call (see
 *   [Trc20TransferAbi]). Unlike `createtransaction`, this was confirmed live to work even for a
 *   never-activated owner account.
 *
 * ## Signing
 * Tron's signature is `ECDSA_sign(privateKey, sha256(raw_data))` over secp256k1 - the same curve/primitive this
 * app already uses for Ethereum. [io.novafoundation.nova.feature_account_api.data.signer.NovaSigner.signRaw]
 * (backed by `substrate_sdk_android`'s `Signer.sign(MultiChainEncryption.Ethereum, message, keypair, skipHashing)`
 * -> `web3j`'s `Sign.signMessage(hash, keyPair, needToHash = false)`) already supports signing a pre-computed
 * hash directly via `SignerPayloadRaw.skipMessageHashing = true` - this is exactly the primitive Ethereum-style
 * raw-hash signing needs, and it is reused as-is here. No new cryptographic code or library was added; only the
 * hash fed into it differs from the EVM path (`sha256(raw_data)` here vs. an EIP-155 RLP-based digest there).
 *
 * `web3j`'s `Sign.signMessage` always left-pads `r`/`s` to exactly 32 bytes and encodes `v` as `27/28` (confirmed
 * by reading `web3j`'s `Sign.java` source) - which is byte-for-byte the same `r(32) + s(32) + v(1)` = 65-byte
 * compact signature format Tron expects (confirmed against `tronweb`'s own `ECKeySign` implementation, and
 * independently confirmed live against Shasta testnet - see the Phase 2 implementation notes).
 *
 * ## Broadcast
 * `POST /wallet/broadcasttransaction` with the full unsigned transaction object (not just `raw_data_hex`) plus
 * `signature: [<65-byte hex signature>]`.
 */
class RealTronTransactionService(
    private val accountRepository: AccountRepository,
    private val signerProvider: SignerProvider,
    private val tronGridApi: TronGridApi,
) : TronTransactionService {

    override suspend fun calculateFee(chain: Chain, origin: TransactionOrigin, recipient: AccountId, intent: TronTransactionIntent): Fee {
        val submittingMetaAccount = accountRepository.requireMetaAccountFor(origin, chain.id)
        val ownerAccountId = submittingMetaAccount.requireAccountIdIn(chain)
        val baseUrl = chain.requireTronGridBaseUrl()
        val ownerHex = ownerAccountId.toTronHexAddress()

        val feeSun = when (intent) {
            is TronTransactionIntent.Native -> estimateNativeFee(baseUrl, ownerHex, recipient, intent.amountSun)
            is TronTransactionIntent.Trc20Transfer -> estimateTrc20FeeFromContractHex(baseUrl, ownerHex, recipient, intent.contractAddress.tronAddressToHexAddress(), intent.amountSun)
        }

        return TronFee(feeSun, SubmissionOrigin.singleOrigin(ownerAccountId), chain.commissionAsset)
    }

    override suspend fun transact(
        chain: Chain,
        origin: TransactionOrigin,
        recipient: AccountId,
        presetFee: Fee?,
        intent: TronTransactionIntent
    ): Result<ExtrinsicSubmission> = runCatching {
        val submittingMetaAccount = accountRepository.requireMetaAccountFor(origin, chain.id)
        val ownerAccountId = submittingMetaAccount.requireAccountIdIn(chain)
        val baseUrl = chain.requireTronGridBaseUrl()
        val ownerHex = ownerAccountId.toTronHexAddress()
        val recipientHex = recipient.toTronHexAddress()

        val unsigned = when (intent) {
            is TronTransactionIntent.Native -> tronGridApi.createNativeTransfer(baseUrl, ownerHex, recipientHex, intent.amountSun)

            is TronTransactionIntent.Trc20Transfer -> {
                val contractHex = intent.contractAddress.tronAddressToHexAddress()
                val parameterHex = Trc20TransferAbi.encodeTransferParameters(recipient, intent.amountSun)
                val feeLimit = feeLimitFor(presetFee, baseUrl, ownerHex, recipient, contractHex, intent.amountSun)

                tronGridApi.triggerSmartContract(
                    baseUrl = baseUrl,
                    ownerHexAddress = ownerHex,
                    contractHexAddress = contractHex,
                    functionSelector = Trc20TransferAbi.TRANSFER_FUNCTION_SELECTOR,
                    parameterHex = parameterHex,
                    feeLimitSun = feeLimit
                ).transaction ?: error("TronGrid returned no transaction from triggersmartcontract")
            }
        }

        val txHash = signAndBroadcast(baseUrl, unsigned, submittingMetaAccount, ownerAccountId)

        ExtrinsicSubmission(
            hash = txHash,
            submissionOrigin = SubmissionOrigin.singleOrigin(ownerAccountId),
            callExecutionType = CallExecutionType.IMMEDIATE,
            submissionHierarchy = SubmissionHierarchy(submittingMetaAccount, CallExecutionType.IMMEDIATE)
        )
    }

    override suspend fun transactAndAwaitExecution(
        chain: Chain,
        origin: TransactionOrigin,
        recipient: AccountId,
        presetFee: Fee?,
        intent: TronTransactionIntent
    ): Result<TransactionExecution> {
        // Tron transactions execute atomically with inclusion (no separate "prepare" step, same as EVM) - so
        // successful broadcast is already a strong signal. We do not poll for block confirmation here since
        // this method sits outside the primary send flow's critical path (`SendInteractor`/`RealSendUseCase`
        // only ever call `transact`, not this) - see Phase 2 implementation notes for what remains unverified.
        return transact(chain, origin, recipient, presetFee, intent).map { TransactionExecution.Tron(it.hash) }
    }

    private suspend fun signAndBroadcast(
        baseUrl: String,
        unsigned: TronUnsignedTransactionResponse,
        metaAccount: MetaAccount,
        ownerAccountId: AccountId
    ): String {
        val rawDataHex = requireNotNull(unsigned.rawDataHex) { "TronGrid returned no raw_data_hex" }
        val messageHash = rawDataHex.fromHex().sha256()

        check(unsigned.txID == null || messageHash.toHexString(withPrefix = false) == unsigned.txID) {
            "sha256(raw_data) does not match the txID TronGrid reported - refusing to sign a possibly-tampered transaction"
        }

        val signer = signerProvider.rootSignerFor(metaAccount)
        val signedRaw = signer.signRaw(SignerPayloadRaw(message = messageHash, accountId = ownerAccountId, skipMessageHashing = true))
        val signature = signedRaw.toEcdsaSignatureData()

        // Tron's compact signature format is r(32) + s(32) + v(1), v = 27/28 - byte-for-byte identical to what
        // web3j's Sign.SignatureData already produces for Ethereum signing (see class doc for verification notes).
        val signatureBytes = signature.r + signature.s + signature.v
        val signatureHex = signatureBytes.toHexString(withPrefix = false)

        return tronGridApi.broadcastTransaction(baseUrl, unsigned, signatureHex)
    }

    private suspend fun feeLimitFor(
        presetFee: Fee?,
        baseUrl: String,
        ownerHex: String,
        recipient: AccountId,
        contractHex: String,
        amountSun: BigInteger
    ): BigInteger {
        val estimatedFee = presetFee?.castOrNull<TronFee>()?.amount
            ?: estimateTrc20FeeFromContractHex(baseUrl, ownerHex, recipient, contractHex, amountSun)

        return (estimatedFee * BigInteger.valueOf(3)).coerceIn(MIN_FEE_LIMIT_SUN, MAX_FEE_LIMIT_SUN)
    }

    private suspend fun estimateNativeFee(baseUrl: String, ownerHex: String, recipient: AccountId, amountSun: BigInteger): BigInteger {
        val unsigned = tronGridApi.createNativeTransfer(baseUrl, ownerHex, recipient.toTronHexAddress(), amountSun)
        val txSizeBytes = requireNotNull(unsigned.rawDataHex) { "TronGrid returned no raw_data_hex" }.length / 2

        val resource = runCatching { tronGridApi.getAccountResource(baseUrl, ownerHex) }.getOrDefault(EMPTY_RESOURCE)
        val bandwidthPrice = chainParameterOrDefault(baseUrl, TRANSACTION_FEE_PARAM_KEY, FALLBACK_BANDWIDTH_FEE_SUN)

        val bandwidthShortfall = shortfall(txSizeBytes.toLong(), resource.availableBandwidth())

        return bandwidthShortfall.toBigInteger() * bandwidthPrice
    }

    private suspend fun estimateTrc20FeeFromContractHex(baseUrl: String, ownerHex: String, recipient: AccountId, contractHex: String, amountSun: BigInteger): BigInteger {
        val parameterHex = Trc20TransferAbi.encodeTransferParameters(recipient, amountSun)

        val dryRun = runCatching {
            tronGridApi.triggerConstantContract(baseUrl, ownerHex, contractHex, Trc20TransferAbi.TRANSFER_FUNCTION_SELECTOR, parameterHex)
        }.getOrNull()

        // A dry-run revert (e.g. the sender doesn't yet hold the token) still reports the energy spent up to the
        // revert point, which remains a meaningful (if slightly different) estimate - it is used as-is rather
        // than special-cased, only a wholly-failed HTTP call falls back to the conservative constant.
        val energyUsed = dryRun?.energyUsed ?: FALLBACK_TRC20_ENERGY_UNITS
        val txSizeBytes = dryRun?.transaction?.rawDataHex?.let { it.length / 2L } ?: FALLBACK_TRC20_TX_SIZE_BYTES

        val resource = runCatching { tronGridApi.getAccountResource(baseUrl, ownerHex) }.getOrDefault(EMPTY_RESOURCE)
        val bandwidthPrice = chainParameterOrDefault(baseUrl, TRANSACTION_FEE_PARAM_KEY, FALLBACK_BANDWIDTH_FEE_SUN)
        val energyPrice = chainParameterOrDefault(baseUrl, ENERGY_FEE_PARAM_KEY, FALLBACK_ENERGY_FEE_SUN)

        val bandwidthShortfall = shortfall(txSizeBytes, resource.availableBandwidth())
        val energyShortfall = shortfall(energyUsed, resource.availableEnergy())

        return bandwidthShortfall.toBigInteger() * bandwidthPrice + energyShortfall.toBigInteger() * energyPrice
    }

    private suspend fun chainParameterOrDefault(baseUrl: String, key: String, default: BigInteger): BigInteger {
        val params = runCatching { tronGridApi.getChainParameters(baseUrl) }.getOrNull()

        return params?.get(key)?.toBigInteger() ?: default
    }

    private fun shortfall(needed: Long, available: Long): Long = (needed - available.coerceAtLeast(0)).coerceAtLeast(0)

    private fun TronAccountResourceResponse.availableBandwidth(): Long =
        (freeNetLimit - freeNetUsed).coerceAtLeast(0) + (netLimit - netUsed).coerceAtLeast(0)

    private fun TronAccountResourceResponse.availableEnergy(): Long =
        (energyLimit - energyUsed).coerceAtLeast(0)
}
