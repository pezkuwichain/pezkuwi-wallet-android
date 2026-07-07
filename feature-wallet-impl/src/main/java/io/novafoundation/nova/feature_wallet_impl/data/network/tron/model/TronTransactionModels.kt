package io.novafoundation.nova.feature_wallet_impl.data.network.tron.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Request/response shapes for TronGrid's transaction-construction/broadcast endpoints (`/wallet/*`).
 *
 * All requests are sent with `"visible": false`, i.e. addresses are hex-encoded (`41` prefix byte ++ 20-byte
 * accountId, see `toTronHexAddress`) rather than Base58Check. Every shape below was confirmed against
 * TronGrid's Shasta testnet (`https://api.shasta.trongrid.io`) with live HTTP calls - see the Phase 2
 * implementation notes for the exact request/response pairs that were captured.
 */

class TronCreateTransactionRequest(
    @SerializedName("owner_address") val ownerAddress: String,
    @SerializedName("to_address") val toAddress: String,
    val amount: Long,
    val visible: Boolean = false,
)

class TronTriggerContractRequest(
    @SerializedName("owner_address") val ownerAddress: String,
    @SerializedName("contract_address") val contractAddress: String,
    @SerializedName("function_selector") val functionSelector: String,
    val parameter: String,
    @SerializedName("fee_limit") val feeLimit: Long? = null,
    @SerializedName("call_value") val callValue: Long = 0,
    val visible: Boolean = false,
)

class TronAddressRequest(
    val address: String,
    val visible: Boolean = false,
)

/**
 * Shape of the unsigned transaction returned by both `/wallet/createtransaction` (flat, at the top level) and
 * `/wallet/triggersmartcontract`/`/wallet/triggerconstantcontract` (nested under a `transaction` key - see
 * [TronTriggerContractResponse]).
 *
 * `rawData` is kept as an opaque [JsonObject] rather than being modeled field-by-field: its contents differ
 * between contract types (`TransferContract` vs `TriggerSmartContract`) and it is never interpreted by this
 * client - it is only ever echoed back verbatim into the broadcast request alongside the signature. The
 * cryptographically-authoritative value is [rawDataHex] (`txID == sha256(rawDataHex bytes)`, confirmed live).
 *
 * `error` is populated (HTTP 200, not an HTTP error) when construction fails, e.g. an unactivated owner account
 * trying to build a native TRX transfer returns `{"Error": "... no OwnerAccount."}`.
 */
class TronUnsignedTransactionResponse(
    val visible: Boolean? = null,
    val txID: String? = null,
    @SerializedName("raw_data") val rawData: JsonObject? = null,
    @SerializedName("raw_data_hex") val rawDataHex: String? = null,
    @SerializedName("Error") val error: String? = null,
)

class TronContractCallResult(
    val result: Boolean = false,
    val code: String? = null,
    val message: String? = null,
)

/**
 * Response of both `/wallet/triggerconstantcontract` (read-only dry run, used for TRC20 fee/energy estimation)
 * and `/wallet/triggersmartcontract` (real construction, used for the actual TRC20 transfer).
 */
class TronTriggerContractResponse(
    val result: TronContractCallResult? = null,
    @SerializedName("energy_used") val energyUsed: Long? = null,
    @SerializedName("constant_result") val constantResult: List<String>? = null,
    val transaction: TronUnsignedTransactionResponse? = null,
)

class TronBroadcastRequest(
    val visible: Boolean,
    val txID: String,
    @SerializedName("raw_data") val rawData: JsonObject,
    @SerializedName("raw_data_hex") val rawDataHex: String,
    val signature: List<String>,
)

/**
 * On success: `{"result": true, "txid": "..."}`.
 * On failure: `{"code": "CONTRACT_VALIDATE_ERROR", "txid": "...", "message": "<hex-encoded ascii>"}` - confirmed
 * live by broadcasting a validly-signed but unfunded-account transaction, e.g.
 * `message` hex-decodes to `"Contract validate error : account [...] does not exist"`.
 */
class TronBroadcastResponse(
    val result: Boolean? = null,
    val txid: String? = null,
    val code: String? = null,
    val message: String? = null,
)

class TronChainParameter(
    val key: String,
    val value: Long = 0,
)

class TronChainParametersResponse(
    @SerializedName("chainParameter") val chainParameter: List<TronChainParameter> = emptyList(),
)

/**
 * Subset of `/wallet/getaccountresource` fields relevant to fee estimation. Fields are omitted by TronGrid
 * (rather than sent as `0`) when their value is zero - confirmed live - hence all default to `0`.
 */
class TronAccountResourceResponse(
    val freeNetLimit: Long = 0,
    val freeNetUsed: Long = 0,
    @SerializedName("NetLimit") val netLimit: Long = 0,
    @SerializedName("NetUsed") val netUsed: Long = 0,
    @SerializedName("EnergyLimit") val energyLimit: Long = 0,
    @SerializedName("EnergyUsed") val energyUsed: Long = 0,
)
