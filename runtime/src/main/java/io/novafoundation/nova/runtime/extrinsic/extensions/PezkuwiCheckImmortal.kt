package io.novafoundation.nova.runtime.extrinsic.extensions

import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.FixedValueTransactionExtension

/**
 * Custom CheckMortality extension for Pezkuwi chains using IMMORTAL era.
 *
 * Pezkuwi uses pezsp_runtime.generic.era.Era which is a DictEnum with variants:
 * - Immortal (encoded as 0x00)
 * - Mortal1(u8), Mortal2(u8), ..., Mortal255(u8)
 *
 * This extension uses Immortal era with genesis hash, which matches how @pezkuwi/api signs.
 *
 * @param genesisHash The chain's genesis hash (32 bytes) for the signer payload
 */
class PezkuwiCheckImmortal(
    genesisHash: ByteArray
) : FixedValueTransactionExtension(
    name = "CheckMortality",
    implicit = genesisHash, // Genesis hash goes into signer payload for immortal transactions
    explicit = DictEnum.Entry<Any?>("Immortal", null) // Immortal variant - unit type with no value
)
