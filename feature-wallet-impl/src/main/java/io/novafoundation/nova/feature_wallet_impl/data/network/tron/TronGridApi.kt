package io.novafoundation.nova.feature_wallet_impl.data.network.tron

import io.novafoundation.nova.common.utils.toTronHexAddress
import io.novafoundation.nova.common.utils.tronAddressToHexAddress
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAccountResourceResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAddressRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronBroadcastRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronBroadcastResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronCreateTransactionRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronTriggerContractRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronTriggerContractResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronUnsignedTransactionResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.Trc20TransferAbi
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.runtime.AccountId
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.math.BigInteger

/**
 * Thrown whenever TronGrid reports a failure via an HTTP-200 body (rather than an HTTP error status), which is
 * how most `/wallet/` endpoints signal validation/execution failures, e.g.
 * `{"Error": "... no OwnerAccount."}` from `createtransaction`, or
 * `{"code": "CONTRACT_VALIDATE_ERROR", "message": "<hex>"}` from `broadcasttransaction`.
 */
class TronApiException(message: String) : Exception(message)

interface TronGridApi {

    suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance

    /**
     * Reads via an on-chain `balanceOf(address)` call (`triggerconstantcontract`), NOT `/v1/accounts` - a
     * TRC-20 balance lives in the token contract's own storage, not in the holder's Account object, so an
     * address that has only ever received TRC-20 tokens (never native TRX, never otherwise "activated") has no
     * Account object at all and `/v1/accounts` returns empty for it regardless of its real token balance.
     * Confirmed live: a wallet holding exactly 5 USDT-TRC20 and zero TRX/activation history returned `data: []`
     * from `/v1/accounts` while `balanceOf` correctly returned 5000000.
     */
    suspend fun fetchTrc20Balance(baseUrl: String, holderAccountId: AccountId, contractAddress: String): Balance

    /**
     * Builds an unsigned native TRX transfer via `POST /wallet/createtransaction`.
     * [ownerHexAddress]/[toHexAddress] must be in hex form (`41`-prefixed), matching `visible: false`.
     *
     * Note: TronGrid refuses to build this for an owner account that has never been activated on-chain
     * (confirmed live: returns `{"Error": "... no OwnerAccount."}`) - unlike [triggerSmartContract], which
     * happily builds a transaction for an unfunded/unactivated owner.
     */
    suspend fun createNativeTransfer(baseUrl: String, ownerHexAddress: String, toHexAddress: String, amountSun: BigInteger): TronUnsignedTransactionResponse

    /**
     * Read-only dry run via `POST /wallet/triggerconstantcontract` - does not require the owner account to hold
     * any TRX and does not touch chain state. Used to estimate the `energy_used` an actual TRC-20 call would
     * cost (see [TronTriggerContractResponse.energyUsed]).
     */
    suspend fun triggerConstantContract(
        baseUrl: String,
        ownerHexAddress: String,
        contractHexAddress: String,
        functionSelector: String,
        parameterHex: String
    ): TronTriggerContractResponse

    /**
     * Builds an unsigned TRC-20 contract call via `POST /wallet/triggersmartcontract`. Unlike
     * [createNativeTransfer], this works even for an owner account that has never been activated on-chain
     * (confirmed live).
     */
    suspend fun triggerSmartContract(
        baseUrl: String,
        ownerHexAddress: String,
        contractHexAddress: String,
        functionSelector: String,
        parameterHex: String,
        feeLimitSun: BigInteger
    ): TronTriggerContractResponse

    /**
     * Signs-and-submits via `POST /wallet/broadcasttransaction`. The full [unsigned] transaction (including its
     * `raw_data` object, not just `raw_data_hex`) must be echoed back verbatim alongside the signature - sending
     * only `raw_data_hex` + `signature` was confirmed live to fail with a deserialization error on TronGrid's side.
     *
     * @return the transaction hash (`txID`) on success.
     * @throws TronApiException if TronGrid rejects the broadcast (invalid signature, insufficient balance, etc.)
     */
    suspend fun broadcastTransaction(baseUrl: String, unsigned: TronUnsignedTransactionResponse, signatureHex: String): String

    /** `key -> value` map from `GET /wallet/getchainparameters`, e.g. `getEnergyFee` (sun/energy), `getTransactionFee` (sun/byte). */
    suspend fun getChainParameters(baseUrl: String): Map<String, Long>

    suspend fun getAccountResource(baseUrl: String, addressHex: String): TronAccountResourceResponse
}

class RealTronGridApi(
    private val retrofitApi: RetrofitTronGridApi
) : TronGridApi {

    // TronGrid's public (no API key) endpoint rate-limits aggressively - confirmed live to return a bare HTTP
    // 429 under normal, human-paced usage (not just load testing) once a handful of requests land in a short
    // window. Without this, a 429 on any call in the send flow (fee estimation re-runs on every keystroke,
    // broadcast, etc.) surfaced straight to the user as a raw error dialog, and the only way through was to
    // keep tapping Confirm until a request happened to land outside the rate-limit window. Retrying here means
    // every TronGrid call gets this transparently, not just the ones a caller remembered to wrap.
    private suspend fun <T> retryOn429(maxAttempts: Int = 4, block: suspend () -> T): T {
        repeat(maxAttempts - 1) { attempt ->
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code() != 429) throw e
                delay(1_000L * (attempt + 1))
            }
        }
        return block()
    }

    override suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance {
        val accountData = fetchAccountData(baseUrl, address) ?: return BigInteger.ZERO

        return accountData.balance?.toBigInteger() ?: BigInteger.ZERO
    }

    override suspend fun fetchTrc20Balance(baseUrl: String, holderAccountId: AccountId, contractAddress: String): Balance {
        val response = triggerConstantContract(
            baseUrl = baseUrl,
            ownerHexAddress = holderAccountId.toTronHexAddress(),
            contractHexAddress = contractAddress.tronAddressToHexAddress(),
            functionSelector = Trc20TransferAbi.BALANCE_OF_FUNCTION_SELECTOR,
            parameterHex = Trc20TransferAbi.encodeBalanceOfParameters(holderAccountId)
        )

        val resultHex = response.constantResult?.firstOrNull() ?: return BigInteger.ZERO

        return runCatching { BigInteger(resultHex, 16) }.getOrDefault(BigInteger.ZERO)
    }

    override suspend fun createNativeTransfer(
        baseUrl: String,
        ownerHexAddress: String,
        toHexAddress: String,
        amountSun: BigInteger
    ): TronUnsignedTransactionResponse {
        val request = TronCreateTransactionRequest(
            ownerAddress = ownerHexAddress,
            toAddress = toHexAddress,
            amount = amountSun.toLongExactOrThrow("amount")
        )

        val response = retryOn429 { retrofitApi.createTransaction(walletUrl(baseUrl, "createtransaction"), request) }

        return response.requireConstructed()
    }

    override suspend fun triggerConstantContract(
        baseUrl: String,
        ownerHexAddress: String,
        contractHexAddress: String,
        functionSelector: String,
        parameterHex: String
    ): TronTriggerContractResponse {
        val request = TronTriggerContractRequest(
            ownerAddress = ownerHexAddress,
            contractAddress = contractHexAddress,
            functionSelector = functionSelector,
            parameter = parameterHex
        )

        return retryOn429 { retrofitApi.triggerConstantContract(walletUrl(baseUrl, "triggerconstantcontract"), request) }
    }

    override suspend fun triggerSmartContract(
        baseUrl: String,
        ownerHexAddress: String,
        contractHexAddress: String,
        functionSelector: String,
        parameterHex: String,
        feeLimitSun: BigInteger
    ): TronTriggerContractResponse {
        val request = TronTriggerContractRequest(
            ownerAddress = ownerHexAddress,
            contractAddress = contractHexAddress,
            functionSelector = functionSelector,
            parameter = parameterHex,
            feeLimit = feeLimitSun.toLongExactOrThrow("feeLimit")
        )

        val response = retryOn429 { retrofitApi.triggerSmartContract(walletUrl(baseUrl, "triggersmartcontract"), request) }

        if (response.result?.result != true) {
            throw TronApiException(response.result?.message ?: response.result?.code ?: "triggersmartcontract failed without a message")
        }

        // Only validate that a transaction was actually returned - callers read [TronTriggerContractResponse.transaction] themselves.
        response.transaction?.requireConstructed()

        return response
    }

    override suspend fun broadcastTransaction(baseUrl: String, unsigned: TronUnsignedTransactionResponse, signatureHex: String): String {
        val txId = requireNotNull(unsigned.txID) { "Cannot broadcast a transaction without a txID" }

        val request = TronBroadcastRequest(
            visible = unsigned.visible ?: false,
            txID = txId,
            rawData = requireNotNull(unsigned.rawData) { "Cannot broadcast a transaction without raw_data" },
            rawDataHex = requireNotNull(unsigned.rawDataHex) { "Cannot broadcast a transaction without raw_data_hex" },
            signature = listOf(signatureHex)
        )

        // Safe to retry on 429 specifically: a 429 means TronGrid rejected the request before processing it
        // (rate limit), not that the transaction may have already been broadcast - unlike a timeout, it can't
        // cause a double-send.
        val response = retryOn429 { retrofitApi.broadcastTransaction(walletUrl(baseUrl, "broadcasttransaction"), request) }

        if (response.result != true) {
            throw TronApiException(response.decodeErrorMessage())
        }

        return response.txid ?: txId
    }

    override suspend fun getChainParameters(baseUrl: String): Map<String, Long> {
        return retryOn429 { retrofitApi.getChainParameters(walletUrl(baseUrl, "getchainparameters")) }
            .chainParameter
            .associate { it.key to it.value }
    }

    override suspend fun getAccountResource(baseUrl: String, addressHex: String): TronAccountResourceResponse {
        return retryOn429 { retrofitApi.getAccountResource(walletUrl(baseUrl, "getaccountresource"), TronAddressRequest(address = addressHex)) }
    }

    private suspend fun fetchAccountData(baseUrl: String, address: String) = retryOn429 {
        retrofitApi.getAccount(url = accountUrl(baseUrl, address))
    }.data?.firstOrNull()

    private fun accountUrl(baseUrl: String, address: String): String {
        return "${baseUrl.trimEnd('/')}/v1/accounts/$address"
    }

    private fun walletUrl(baseUrl: String, method: String): String {
        return "${baseUrl.trimEnd('/')}/wallet/$method"
    }

    private fun TronUnsignedTransactionResponse.requireConstructed(): TronUnsignedTransactionResponse {
        if (error != null) throw TronApiException(error)
        requireNotNull(rawDataHex) { "TronGrid returned no raw_data_hex and no Error" }
        requireNotNull(txID) { "TronGrid returned no txID and no Error" }

        return this
    }

    private fun TronBroadcastResponse.decodeErrorMessage(): String {
        val decodedMessage = message?.let { hex -> runCatching { hex.fromHex().decodeToString() }.getOrNull() }

        return decodedMessage ?: code ?: "broadcasttransaction failed without a message"
    }

    private fun BigInteger.toLongExactOrThrow(fieldName: String): Long {
        return runCatching { longValueExact() }
            .getOrElse { throw IllegalArgumentException("$fieldName overflows Long: $this") }
    }
}
