package io.novafoundation.nova.common.utils

import android.util.Log
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.definitions.registry.TypeRegistry
import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.MULTI_ADDRESS_ID
import io.novasama.substrate_sdk_android.runtime.definitions.types.primitives.FixedByteArray
import io.novasama.substrate_sdk_android.runtime.definitions.types.skipAliases

private const val TAG = "PezkuwiAddressConstructor"

/**
 * Custom address constructor that handles Pezkuwi chains which use different type names.
 * Pezkuwi uses "pezsp_runtime::multiaddress::MultiAddress" instead of standard "Address".
 */
object PezkuwiAddressConstructor {

    private val ADDRESS_TYPE_NAMES = listOf(
        "Address",
        "MultiAddress",
        "sp_runtime::multiaddress::MultiAddress",
        "pezsp_runtime::multiaddress::MultiAddress"
    )

    /**
     * Constructs an address instance compatible with both standard Substrate and Pezkuwi chains.
     * Checks the actual type structure to determine the correct encoding format.
     */
    fun constructInstance(typeRegistry: TypeRegistry, accountId: AccountId): Any {
        // Try to find the address type
        var foundTypeName: String? = null
        val addressType = ADDRESS_TYPE_NAMES.firstNotNullOfOrNull { name ->
            typeRegistry[name]?.also { foundTypeName = name }
        }

        Log.d(TAG, "Found address type: $foundTypeName, type class: ${addressType?.javaClass?.simpleName}")

        // If no address type found, return the raw accountId (for chains with simple AccountId)
        if (addressType == null) {
            Log.d(TAG, "No address type found, returning raw accountId")
            return accountId
        }

        val resolvedType = addressType.skipAliases()
        Log.d(TAG, "Resolved type after skipAliases: ${resolvedType?.javaClass?.simpleName}, name: ${resolvedType?.name}")

        // Check the actual type structure
        return when (resolvedType) {
            is DictEnum -> {
                Log.d(TAG, "Type is DictEnum with variants: ${resolvedType.elements.keys}")
                // MultiAddress type - wrap in Id variant
                DictEnum.Entry(MULTI_ADDRESS_ID, accountId)
            }
            is FixedByteArray -> {
                Log.d(TAG, "Type is FixedByteArray with length: ${resolvedType.length}, returning raw accountId")
                // GenericAccountId or similar - return raw
                accountId
            }
            null -> {
                Log.d(TAG, "Resolved type is null, returning raw accountId for Pezkuwi")
                // For Pezkuwi, if alias doesn't resolve, try raw accountId
                accountId
            }
            else -> {
                Log.d(TAG, "Unknown type: ${resolvedType.javaClass.simpleName}, returning raw accountId")
                // Unknown type, try raw accountId instead of DictEnum
                accountId
            }
        }
    }
}
