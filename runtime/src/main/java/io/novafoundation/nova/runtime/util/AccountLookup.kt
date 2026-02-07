package io.novafoundation.nova.runtime.util

import android.util.Log
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.definitions.types.RuntimeType
import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.MULTI_ADDRESS_ID
import io.novasama.substrate_sdk_android.runtime.definitions.types.primitives.FixedByteArray
import io.novasama.substrate_sdk_android.runtime.definitions.types.skipAliases

private const val TAG = "AccountLookup"

fun RuntimeType<*, *>.constructAccountLookupInstance(accountId: AccountId): Any {
    val resolvedType = skipAliases()
    Log.d(TAG, "Type name: ${this.name}, resolved type: ${resolvedType?.javaClass?.simpleName}")

    return when (resolvedType) {
        is DictEnum -> {
            // MultiAddress type - wrap in the appropriate variant
            // Standard chains use "Id", but Pezkuwi uses numeric variants like "0"
            val variantNames = resolvedType.elements.values.map { it.name }
            Log.d(TAG, "DictEnum variants: $variantNames")

            // Use "Id" if available (standard chains), otherwise use the first variant (index 0)
            // which is always the AccountId variant in MultiAddress
            val idVariantName = if (variantNames.contains(MULTI_ADDRESS_ID)) {
                MULTI_ADDRESS_ID
            } else {
                // For chains like Pezkuwi that use numeric variant names
                resolvedType.elements[0]?.name ?: MULTI_ADDRESS_ID
            }
            Log.d(TAG, "Using variant name: $idVariantName")
            DictEnum.Entry(idVariantName, accountId)
        }
        is FixedByteArray -> {
            // GenericAccountId or similar - return raw accountId
            Log.d(TAG, "FixedByteArray type, returning raw accountId")
            accountId
        }
        null -> {
            // For Pezkuwi chains where alias might not resolve properly
            // Check if the original type name suggests MultiAddress
            Log.d(TAG, "Resolved type is null, checking original type name: ${this.name}")
            if (this.name?.contains("MultiAddress") == true || this.name?.contains("multiaddress") == true) {
                // For unresolved MultiAddress types, use "0" which is the standard first variant (AccountId)
                Log.d(TAG, "Type name contains MultiAddress, using DictEnum.Entry with variant 0")
                DictEnum.Entry("0", accountId)
            } else {
                Log.d(TAG, "Unknown type with null resolution, returning raw accountId")
                accountId
            }
        }
        else -> {
            // Unknown type - for Pezkuwi compatibility, try raw accountId instead of throwing
            Log.w(TAG, "Unknown address type: ${this.name} (${resolvedType.javaClass.simpleName}), trying raw accountId")
            accountId
        }
    }
}
