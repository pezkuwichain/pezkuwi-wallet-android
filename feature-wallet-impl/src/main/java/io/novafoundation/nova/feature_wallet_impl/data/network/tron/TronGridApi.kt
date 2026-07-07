package io.novafoundation.nova.feature_wallet_impl.data.network.tron

import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAccountResourceResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAddressRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronBroadcastRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronBroadcastResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronCreateTransactionRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronTriggerContractRequest
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronTriggerContractResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronUnsignedTransactionResponse
import io.novasama.substrate_sdk_android.extensions.fromHex
import java.math.BigInteger

/**
 * Thrown whenever TronGrid reports a failure via an HTTP-200 body (rather than an HTTP error status), which is
 * how most `/wallet/*` endpoints signal validation/execution failures, e.g.
 * `{"Error": "... no OwnerAccount."}` from `createtransaction`, or
 * `{"code": "CONTRACT_VALIDATE_ERROR", "message": "<hex>"}` from `broadcasttransaction`.
 */
class TronApiException(message: String) : Exception(message)

interface TronGridApi {

    suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance

    suspend fun fetchTrc20Balance(baseUrl: String, address: String, contractAddress: String): Balance

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

    override suspend fun fetchNativeBalance(baseUrl: String, address: String): Balance {
        val accountData = fetchAccountData(baseUrl, address) ?: return BigInteger.ZERO

        return accountData.balance?.toBigInteger() ?: BigInteger.ZERO
    }

    override suspend fun fetchTrc20Balance(baseUrl: String, address: String, contractAddress: String): Balance {
        val accountData = fetchAccountData(baseUrl, address) ?: return BigInteger.ZERO

        val rawBalance = accountData.trc20.orEmpty()
            .firstNotNullOfOrNull { entry -> entry[contractAddress] }

        return rawBalance?.toBigIntegerOrNull() ?: BigInteger.ZERO
    }

    override suspend fun createNativeTransfer(baseUrl: String, ownerHexAddress: String, toHexAddress: String, amountSun: BigInteger): TronUnsignedTransactionResponse {
        val request = TronCreateTransactionRequest(
            ownerAddress = ownerHexAddress,
            toAddress = toHexAddress,
            amount = amountSun.toLongExactOrThrow("amount")
        )

        val response = retrofitApi.createTransaction(walletUrl(baseUrl, "createtransaction"), request)

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

        return retrofitApi.triggerConstantContract(walletUrl(baseUrl, "triggerconstantcontract"), request)
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

        val response = retrofitApi.triggerSmartContract(walletUrl(baseUrl, "triggersmartcontract"), request)

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

        val response = retrofitApi.broadcastTransaction(walletUrl(baseUrl, "broadcasttransaction"), request)

        if (response.result != true) {
            throw TronApiException(response.decodeErrorMessage())
        }

        return response.txid ?: txId
    }

    override suspend fun getChainParameters(baseUrl: String): Map<String, Long> {
        return retrofitApi.getChainParameters(walletUrl(baseUrl, "getchainparameters"))
            .chainParameter
            .associate { it.key to it.value }
    }

    override suspend fun getAccountResource(baseUrl: String, addressHex: String): TronAccountResourceResponse {
        return retrofitApi.getAccountResource(walletUrl(baseUrl, "getaccountresource"), TronAddressRequest(address = addressHex))
    }

    private suspend fun fetchAccountData(baseUrl: String, address: String) = retrofitApi.getAccount(
        url = accountUrl(baseUrl, address)
    ).data?.firstOrNull()

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
