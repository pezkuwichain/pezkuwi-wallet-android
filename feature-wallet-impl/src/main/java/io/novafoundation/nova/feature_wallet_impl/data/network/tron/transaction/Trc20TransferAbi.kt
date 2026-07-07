package io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction

import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.runtime.AccountId
import java.math.BigInteger

/**
 * Minimal, hand-written Solidity ABI encoding for the single call this client ever makes to a TRC-20 contract:
 * `transfer(address,uint256)`.
 *
 * There is no pre-existing ABI-encoding utility reused here: Phase 1's TRC-20 balance reads
 * (`Trc20AssetBalance`) go through TronGrid's `/v1/accounts` REST endpoint, not an on-chain `balanceOf` call -
 * so no prior ABI-encoding code exists in this codebase.
 *
 * Both parameter types involved (`address`, `uint256`) are static (fixed-size), so encoding is just "left-pad
 * each to 32 bytes and concatenate" - no dynamic-type/offset table is needed. The 4-byte function selector is
 * intentionally NOT computed client-side: TronGrid accepts the human-readable `function_selector` string
 * directly and hashes it server-side (confirmed live against Shasta testnet - a call with
 * `function_selector: "transfer(address,uint256)"` and no client-computed selector correctly resolved to the
 * standard `a9059cbb` selector in the resulting `raw_data`), which avoids needing a keccak256 implementation here.
 */
object Trc20TransferAbi {

    const val TRANSFER_FUNCTION_SELECTOR = "transfer(address,uint256)"

    /**
     * @param recipient raw 20-byte Ethereum/Tron-style account id (NOT the `41`-prefixed Tron hex address -
     * ABI-encoded Solidity `address` parameters use the bare 20-byte form, confirmed live).
     */
    fun encodeTransferParameters(recipient: AccountId, amountSun: BigInteger): String {
        require(recipient.size == 20) { "Tron/EVM-style account id must be 20 bytes, got ${recipient.size}" }
        require(amountSun.signum() >= 0) { "Amount must not be negative, got $amountSun" }

        val addressParam = recipient.toHexString(withPrefix = false).padStart(64, '0')
        val amountParam = amountSun.toString(16).padStart(64, '0')

        return addressParam + amountParam
    }
}
