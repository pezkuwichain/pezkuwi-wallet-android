package io.novafoundation.nova.feature_wallet_impl.data.network.solana.model

/**
 * Solana speaks a single JSON-RPC endpoint (unlike Tron/Bitcoin's path-per-resource REST style) - every call
 * is an HTTP POST of one of these envelopes to the same url, distinguished only by [method]/[params]. See
 * https://solana.com/docs/rpc/http for the spec this mirrors.
 */
class SolanaRpcRequest(
    val method: String,
    val params: List<Any> = emptyList(),
    val jsonrpc: String = "2.0",
    val id: Int = 1,
)

class SolanaRpcError(
    val code: Int,
    val message: String,
)

class SolanaGetBalanceResponse(
    val result: SolanaBalanceResult?,
    val error: SolanaRpcError?,
)

class SolanaBalanceResult(
    /** Lamports (1 SOL = 1_000_000_000 lamports) - Solana's native-coin base unit, same role planks/wei play elsewhere. */
    val value: Long,
)

class SolanaGetLatestBlockhashResponse(
    val result: SolanaLatestBlockhashResult?,
    val error: SolanaRpcError?,
)

class SolanaLatestBlockhashResult(
    val value: SolanaBlockhashValue,
)

class SolanaBlockhashValue(
    /** Base58-encoded, same alphabet/encoding as an account address - decode with [Base58] before use. */
    val blockhash: String,
)

class SolanaGetFeeForMessageResponse(
    val result: SolanaFeeForMessageResult?,
    val error: SolanaRpcError?,
)

class SolanaFeeForMessageResult(
    /** Lamports, or null if the message's blockhash is not found (e.g. too old) - see RealSolanaApi's handling. */
    val value: Long?,
)

class SolanaSendTransactionResponse(
    /** The transaction's signature, base58-encoded - this doubles as the transaction id/hash used for tracking. */
    val result: String?,
    val error: SolanaRpcError?,
)
