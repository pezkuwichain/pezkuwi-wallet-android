package io.novafoundation.nova.runtime.multiNetwork.runtime.types.custom.pezkuwi

import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.PathMatchTypeMapping
import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.PathMatchTypeMapping.Replacement.AliasTo

/**
 * PathMatchTypeMapping for Pezkuwi chains that use pezsp_* and pezframe_* package prefixes
 * instead of the standard sp_* and frame_* prefixes used by Polkadot/Substrate chains.
 *
 * This maps specific Pezkuwi type paths to standard type names during metadata parsing.
 * Aliasing at parse time ensures the type ID mapping also uses the correct type.
 *
 * Era MUST be aliased here (not in RuntimeFactory.addPezkuwiTypeAliases) because the built-in
 * EraType has special encode/decode logic for Era.Mortal/Era.Immortal that the raw DictEnum
 * from metadata doesn't support. Aliasing at parse time ensures the type ID resolves correctly.
 *
 * MultiSignature, MultiAddress: NOT aliased here — their DictEnum versions from metadata are
 * structurally identical to built-ins. RuntimeFactory.addPezkuwiTypeAliases() copies them.
 *
 * Weight types: NOT aliased — SDK doesn't have a WeightV1 type. Parsed as structs from metadata.
 */
fun PezkuwiPathTypeMapping(): PathMatchTypeMapping = PathMatchTypeMapping(
    // Runtime call/event types for Pezkuwi
    "*.RuntimeCall" to AliasTo("GenericCall"),
    "*.RuntimeEvent" to AliasTo("GenericEvent"),
    "*_runtime.Call" to AliasTo("GenericCall"),
    "*_runtime.Event" to AliasTo("GenericEvent"),

    // Era: alias to built-in EraType so ExtrinsicBuilder can encode Era.Mortal/Era.Immortal
    "pezsp_runtime.generic.era.Era" to AliasTo("Era"),
)
