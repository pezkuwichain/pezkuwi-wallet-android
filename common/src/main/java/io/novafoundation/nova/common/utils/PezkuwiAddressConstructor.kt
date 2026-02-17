package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.definitions.registry.TypeRegistry
import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.MULTI_ADDRESS_ID
import io.novasama.substrate_sdk_android.runtime.definitions.types.primitives.FixedByteArray
import io.novasama.substrate_sdk_android.runtime.definitions.types.skipAliases

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
        var foundTypeName: String? = null
        val addressType = ADDRESS_TYPE_NAMES.firstNotNullOfOrNull { name ->
            typeRegistry[name]?.also { foundTypeName = name }
        }

        if (addressType == null) {
            return accountId
        }

        val resolvedType = addressType.skipAliases()

        return when (resolvedType) {
            is DictEnum -> {
                val variantNames = resolvedType.elements.values.map { it.name }

                val idVariantName = if (variantNames.contains(MULTI_ADDRESS_ID)) {
                    MULTI_ADDRESS_ID
                } else {
                    resolvedType.elements[0]?.name ?: MULTI_ADDRESS_ID
                }
                DictEnum.Entry(idVariantName, accountId)
            }
            is FixedByteArray -> {
                accountId
            }
            null -> {
                if (foundTypeName?.contains("MultiAddress") == true || foundTypeName?.contains("multiaddress") == true) {
                    DictEnum.Entry("0", accountId)
                } else {
                    accountId
                }
            }
            else -> {
                accountId
            }
        }
    }
}
