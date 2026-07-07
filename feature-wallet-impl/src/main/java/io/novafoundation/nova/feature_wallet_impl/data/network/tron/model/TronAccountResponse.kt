package io.novafoundation.nova.feature_wallet_impl.data.network.tron.model

/**
 * Response shape of TronGrid's `GET /v1/accounts/{address}`.
 *
 * An account that has never received any TRX/TRC20 transfer is not yet "activated" on-chain and TronGrid
 * returns an empty `data` array for it (not an error) - callers should treat that as a zero balance.
 */
class TronAccountResponse(
    val data: List<TronAccountData>? = null,
    val success: Boolean = true
)

class TronAccountData(
    /**
     * Native TRX balance, denominated in SUN (1 TRX = 1_000_000 SUN), matching this chain's configured `precision: 6`.
     * Absent for freshly-activated accounts that hold TRX but have never been observed with a balance field by the indexer.
     */
    val balance: Long? = null,

    /**
     * List of single-entry maps: TRC20 contract address (Base58Check, same format as our chain config's `contractAddress`) -> balance string.
     * Only contains entries for tokens the account has ever interacted with; a token missing from this list means a zero balance.
     */
    val trc20: List<Map<String, String>>? = null
)
