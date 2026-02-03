package io.novafoundation.nova.runtime.util

import io.novasama.substrate_sdk_android.runtime.RuntimeSnapshot
import io.novasama.substrate_sdk_android.runtime.definitions.types.primitives.FixedByteArray
import io.novasama.substrate_sdk_android.runtime.definitions.types.skipAliases

fun RuntimeSnapshot.isEthereumAddress(): Boolean {
    // Try different address type names used by different chains
    val addressType = typeRegistry["Address"]
        ?: typeRegistry["MultiAddress"]
        ?: typeRegistry["sp_runtime::multiaddress::MultiAddress"]
        ?: typeRegistry["pezsp_runtime::multiaddress::MultiAddress"]
        ?: return false // If no address type found, assume not Ethereum

    val resolvedType = addressType.skipAliases() ?: return false

    return resolvedType is FixedByteArray && resolvedType.length == 20
}
