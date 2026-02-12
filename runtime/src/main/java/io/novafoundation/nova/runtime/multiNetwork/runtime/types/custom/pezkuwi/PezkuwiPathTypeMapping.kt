package io.novafoundation.nova.runtime.multiNetwork.runtime.types.custom.pezkuwi

import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.PathMatchTypeMapping
import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.PathMatchTypeMapping.Replacement.AliasTo

/**
 * PathMatchTypeMapping for Pezkuwi chains that use pezsp_* and pezframe_* package prefixes
 * instead of the standard sp_* and frame_* prefixes used by Polkadot/Substrate chains.
 *
 * This maps specific Pezkuwi type paths to standard type names:
 * - RuntimeCall/RuntimeEvent -> GenericCall/GenericEvent
 *
 * IMPORTANT: Era, MultiSignature, MultiAddress, and Weight types are NOT aliased here.
 * They need to be parsed as actual types from metadata. RuntimeFactory.addPezkuwiTypeAliases()
 * handles copying these types to standard names after parsing.
 *
 * NOTE: Weight types (pezsp_weights.weight_v2.Weight) are NOT aliased because the SDK
 * doesn't have a WeightV1 type defined. They are parsed as structs from metadata.
 */
fun PezkuwiPathTypeMapping(): PathMatchTypeMapping = PathMatchTypeMapping(
    // NOTE: Do NOT alias pezsp_runtime.generic.era.Era, pezsp_runtime.MultiSignature,
    // pezsp_runtime.multiaddress.MultiAddress, or pezsp_weights.weight_v2.Weight here.
    // These need to be parsed as actual types from metadata.
    // RuntimeFactory.addPezkuwiTypeAliases() copies the parsed types to standard names.

    // Runtime call/event types for Pezkuwi
    "*.RuntimeCall" to AliasTo("GenericCall"),
    "*.RuntimeEvent" to AliasTo("GenericEvent"),
    "*_runtime.Call" to AliasTo("GenericCall"),
    "*_runtime.Event" to AliasTo("GenericEvent"),
)
