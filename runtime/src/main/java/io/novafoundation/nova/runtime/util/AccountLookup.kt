package io.novafoundation.nova.runtime.util

import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.definitions.types.RuntimeType
import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.MULTI_ADDRESS_ID
import io.novasama.substrate_sdk_android.runtime.definitions.types.primitives.FixedByteArray
import io.novasama.substrate_sdk_android.runtime.definitions.types.skipAliases

fun RuntimeType<*, *>.constructAccountLookupInstance(accountId: AccountId): Any {
    val resolvedType = skipAliases()

    return when (resolvedType) {
        is DictEnum -> {
            // MultiAddress type - wrap in the appropriate variant
            // Standard chains use "Id", but Pezkuwi uses numeric variants like "0"
            val variantNames = resolvedType.elements.values.map { it.name }

            // Use "Id" if available (standard chains), otherwise use the first variant (index 0)
            // which is always the AccountId variant in MultiAddress
            val idVariantName = if (variantNames.contains(MULTI_ADDRESS_ID)) {
                MULTI_ADDRESS_ID
            } else {
                // For chains like Pezkuwi that use numeric variant names
                resolvedType.elements[0]?.name ?: MULTI_ADDRESS_ID
            }
            DictEnum.Entry(idVariantName, accountId)
        }
        is FixedByteArray -> {
            // GenericAccountId or similar - return raw accountId
            accountId
        }
        null -> {
            // For Pezkuwi chains where alias might not resolve properly
            if (this.name?.contains("MultiAddress") == true || this.name?.contains("multiaddress") == true) {
                DictEnum.Entry("0", accountId)
            } else {
                accountId
            }
        }
        else -> {
            // Unknown type - for Pezkuwi compatibility, try raw accountId
            accountId
        }
    }
}
