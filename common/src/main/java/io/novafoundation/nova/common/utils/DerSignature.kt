package io.novafoundation.nova.common.utils

import java.math.BigInteger

/**
 * DER-encodes a raw secp256k1 ECDSA (r, s) signature the way Bitcoin's script/witness format requires it -
 * unlike Tron/Ethereum, which both use a fixed-size compact r(32)+s(32)+v(1) format (see
 * [io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.RealTronTransactionService]'s
 * doc-comment for that format), Bitcoin signatures are a variable-length ASN.1 DER `SEQUENCE(INTEGER r, INTEGER
 * s)`.
 *
 * `r`/`s` are taken as raw big-endian unsigned 32-byte values - exactly what
 * [io.novasama.substrate_sdk_android]'s `SignatureWrapper.Ecdsa` (reached via `SignedRaw.toEcdsaSignatureData()`)
 * already exposes for Ethereum-style signing, which this app already uses. No new signing call path is needed
 * for Bitcoin: only this pure, standalone encoding step is new.
 */
object DerSignature {

    // secp256k1 curve order n, and n/2 - Bitcoin Core's standardness rules (BIP62) reject a signature whose `s`
    // is greater than n/2 ("high-S"); wallets are expected to always produce the "low-S" of the two equally
    // valid (r, s) and (r, n-s) signatures for a given message, or relay nodes/miners may refuse the transaction.
    private val CURVE_ORDER = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val HALF_CURVE_ORDER = CURVE_ORDER.shiftRight(1)

    /**
     * @param r raw big-endian unsigned 32-byte value
     * @param s raw big-endian unsigned 32-byte value (will be normalized to low-S if not already)
     * @return DER-encoded `SEQUENCE(INTEGER r, INTEGER s)`, WITHOUT the trailing sighash-type byte (the caller
     * appends that when assembling the witness/scriptSig, since it is not part of the DER signature itself).
     */
    fun encode(r: ByteArray, s: ByteArray): ByteArray {
        val rInt = BigInteger(1, r)
        var sInt = BigInteger(1, s)

        if (sInt > HALF_CURVE_ORDER) {
            sInt = CURVE_ORDER.subtract(sInt)
        }

        val rEncoded = encodeInteger(rInt)
        val sEncoded = encodeInteger(sInt)

        val sequenceBody = rEncoded + sEncoded

        return byteArrayOf(0x30, sequenceBody.size.toDerLength()) + sequenceBody
    }

    /**
     * ASN.1 DER INTEGER: tag(0x02) + length + minimal big-endian two's-complement bytes. [BigInteger.toByteArray]
     * already produces minimal big-endian two's-complement (including the leading 0x00 disambiguation byte when
     * the high bit of the first byte would otherwise be set, which would make it read as negative) - since
     * `rInt`/`sInt` are always non-negative here, its output is exactly the DER INTEGER content we need.
     */
    private fun encodeInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02, bytes.size.toDerLength()) + bytes
    }

    private fun Int.toDerLength(): Byte {
        require(this in 0..127) { "DER length $this requires long-form encoding, not expected for a 32-byte ECDSA signature" }
        return toByte()
    }
}
