package io.novafoundation.nova.runtime.extrinsic.extensions

import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.Era
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.FixedValueTransactionExtension
import java.math.BigInteger

/**
 * Custom CheckMortality extension for Pezkuwi chains.
 *
 * Pezkuwi uses pezsp_runtime.generic.era.Era which is a DictEnum with variants:
 * - Immortal
 * - Mortal1(u8), Mortal2(u8), ..., Mortal255(u8)
 *
 * The variant name is "MortalX" where X is the first byte of the encoded era,
 * and the variant's value is the second byte (u8).
 *
 * @param era The mortal era from MortalityConstructor
 * @param blockHash The block hash (32 bytes) for the signer payload
 */
class PezkuwiCheckMortality(
    era: Era.Mortal,
    blockHash: ByteArray
) : FixedValueTransactionExtension(
    name = "CheckMortality",
    implicit = blockHash, // blockHash goes into signer payload
    explicit = createEraEntry(era) // Era as DictEnum.Entry
) {
    companion object {
        /**
         * Creates a DictEnum.Entry for the Era.
         *
         * Standard Era encoding produces 2 bytes:
         * - First byte determines the variant name (Mortal1, Mortal2, ..., Mortal255)
         * - Second byte is the variant's value (u8)
         */
        private fun createEraEntry(era: Era.Mortal): DictEnum.Entry<BigInteger> {
            val period = era.period.toLong()
            val phase = era.phase.toLong()
            val quantizeFactor = maxOf(period shr 12, 1)

            // Calculate the two-byte encoding
            val encoded = ((countTrailingZeroBits(period) - 1).coerceIn(1, 15)) or
                ((phase / quantizeFactor).toInt() shl 4)

            val firstByte = encoded and 0xFF
            val secondByte = (encoded shr 8) and 0xFF

            // DictEnum variant: "MortalX" where X is the first byte
            // Variant value: second byte as u8 (BigInteger)
            return DictEnum.Entry(
                name = "Mortal$firstByte",
                value = BigInteger.valueOf(secondByte.toLong())
            )
        }

        private fun countTrailingZeroBits(value: Long): Int {
            if (value == 0L) return 64
            var n = 0
            var x = value
            while ((x and 1L) == 0L) {
                n++
                x = x shr 1
            }
            return n
        }
    }
}
