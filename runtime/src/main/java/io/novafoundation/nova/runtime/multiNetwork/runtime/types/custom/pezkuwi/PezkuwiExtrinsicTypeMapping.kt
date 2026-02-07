package io.novafoundation.nova.runtime.multiNetwork.runtime.types.custom.pezkuwi

import android.util.Log
import io.novasama.substrate_sdk_android.runtime.definitions.registry.TypePresetBuilder
import io.novasama.substrate_sdk_android.runtime.definitions.registry.alias
import io.novasama.substrate_sdk_android.runtime.definitions.types.Type
import io.novasama.substrate_sdk_android.runtime.definitions.types.instances.ExtrinsicTypes
import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.SiTypeMapping
import io.novasama.substrate_sdk_android.runtime.metadata.v14.PortableType
import io.novasama.substrate_sdk_android.runtime.metadata.v14.paramType
import io.novasama.substrate_sdk_android.runtime.metadata.v14.type
import io.novasama.substrate_sdk_android.scale.EncodableStruct

/**
 * Custom SiTypeMapping for Pezkuwi chains that use pezsp_* package prefixes
 * instead of the standard sp_* prefixes used by Polkadot/Substrate chains.
 *
 * This mapping detects `pezsp_runtime.generic.unchecked_extrinsic.UncheckedExtrinsic`
 * and extracts the Address and Signature type parameters to register them as
 * ExtrinsicTypes.ADDRESS and ExtrinsicTypes.SIGNATURE aliases.
 *
 * Without this mapping, Pezkuwi transactions would fail because the SDK's default
 * AddExtrinsicTypesSiTypeMapping only looks for sp_runtime paths.
 */
private const val PEZSP_UNCHECKED_EXTRINSIC_TYPE = "pezsp_runtime.generic.unchecked_extrinsic.UncheckedExtrinsic"

class PezkuwiExtrinsicTypeMapping : SiTypeMapping {

    override fun map(
        originalDefinition: EncodableStruct<PortableType>,
        suggestedTypeName: String,
        typesBuilder: TypePresetBuilder
    ): Type<*>? {
        // Log all type names that contain "pezsp" and "extrinsic" for debugging
        if (suggestedTypeName.contains("pezsp", ignoreCase = true) && suggestedTypeName.contains("extrinsic", ignoreCase = true)) {
            Log.d("PezkuwiExtrinsicMapping", "Seeing type: $suggestedTypeName")
        }

        if (suggestedTypeName == PEZSP_UNCHECKED_EXTRINSIC_TYPE) {
            Log.d("PezkuwiExtrinsicMapping", "MATCHED! Processing UncheckedExtrinsic type")

            // Extract Address type param and register as "Address" alias
            val addressAdded = addTypeFromTypeParams(
                originalDefinition = originalDefinition,
                typesBuilder = typesBuilder,
                typeParamName = "Address",
                newTypeName = ExtrinsicTypes.ADDRESS
            )
            Log.d("PezkuwiExtrinsicMapping", "Address alias added: $addressAdded")

            // Extract Signature type param and register as "ExtrinsicSignature" alias
            val sigAdded = addTypeFromTypeParams(
                originalDefinition = originalDefinition,
                typesBuilder = typesBuilder,
                typeParamName = "Signature",
                newTypeName = ExtrinsicTypes.SIGNATURE
            )
            Log.d("PezkuwiExtrinsicMapping", "ExtrinsicSignature alias added: $sigAdded")
        }

        // We don't modify any existing type, just add aliases
        return null
    }

    private fun addTypeFromTypeParams(
        originalDefinition: EncodableStruct<PortableType>,
        typesBuilder: TypePresetBuilder,
        typeParamName: String,
        newTypeName: String
    ): Boolean {
        val paramType = originalDefinition.type.paramType(typeParamName)
        Log.d("PezkuwiExtrinsicMapping", "Looking for param '$typeParamName', found: $paramType")

        if (paramType == null) {
            Log.w("PezkuwiExtrinsicMapping", "Could not find type param '$typeParamName' in UncheckedExtrinsic")
            return false
        }

        // Type with type-id name is present in the registry as alias to fully qualified name
        val targetType = paramType.toString()
        Log.d("PezkuwiExtrinsicMapping", "Creating alias: $newTypeName -> $targetType")
        typesBuilder.alias(newTypeName, targetType)
        return true
    }
}
