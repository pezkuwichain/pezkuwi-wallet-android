package io.novafoundation.nova.runtime.extrinsic.signer

import org.bouncycastle.jcajce.provider.digest.Keccak
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SR25519 signing implementation for PezkuwiChain using "bizinikiwi" signing context.
 *
 * This is a port of @pezkuwi/scure-sr25519 which uses Merlin transcripts
 * (built on Strobe128) with a custom signing context.
 *
 * Standard Substrate uses "substrate" context, but Pezkuwi uses "bizinikiwi".
 */
object BizinikiwSr25519Signer {

    private val BIZINIKIWI_CONTEXT = "bizinikiwi".toByteArray(Charsets.UTF_8)

    // Ed25519 curve order
    private val CURVE_ORDER = BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")

    // Strobe128 constants
    private const val STROBE_R = 166

    /**
     * Sign a message using SR25519 with "bizinikiwi" context.
     *
     * @param secretKey 64-byte secret key (32-byte scalar + 32-byte nonce)
     * @param message The message to sign
     * @return 64-byte signature with Schnorrkel marker
     */
    fun sign(secretKey: ByteArray, message: ByteArray): ByteArray {
        require(secretKey.size == 64) { "Secret key must be 64 bytes" }

        // Create signing transcript
        val transcript = SigningContext("SigningContext")
        transcript.label(BIZINIKIWI_CONTEXT)
        transcript.bytes(message)

        // Extract key components
        val keyScalar = decodeScalar(secretKey.copyOfRange(0, 32))
        val nonce = secretKey.copyOfRange(32, 64)
        val publicKey = getPublicKey(secretKey)
        val pubPoint = RistrettoPoint.fromBytes(publicKey)

        // Schnorrkel signing protocol
        transcript.protoName("Schnorr-sig")
        transcript.commitPoint("sign:pk", pubPoint)

        val r = transcript.witnessScalar("signing", nonce)
        val R = RistrettoPoint.BASE.multiply(r)

        transcript.commitPoint("sign:R", R)
        val k = transcript.challengeScalar("sign:c")

        val s = (k.multiply(keyScalar).add(r)).mod(CURVE_ORDER)

        // Build signature
        val signature = ByteArray(64)
        System.arraycopy(R.toBytes(), 0, signature, 0, 32)
        System.arraycopy(scalarToBytes(s), 0, signature, 32, 32)

        // Add Schnorrkel marker
        signature[63] = (signature[63].toInt() or 0x80).toByte()

        return signature
    }

    /**
     * Get public key from secret key.
     */
    fun getPublicKey(secretKey: ByteArray): ByteArray {
        require(secretKey.size == 64) { "Secret key must be 64 bytes" }
        val scalar = decodeScalar(secretKey.copyOfRange(0, 32))
        return RistrettoPoint.BASE.multiply(scalar).toBytes()
    }

    /**
     * Verify a signature.
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        require(signature.size == 64) { "Signature must be 64 bytes" }
        require(publicKey.size == 32) { "Public key must be 32 bytes" }

        // Check Schnorrkel marker
        if ((signature[63].toInt() and 0x80) == 0) {
            return false
        }

        // Extract R and s from signature
        val sBytes = signature.copyOfRange(32, 64)
        sBytes[31] = (sBytes[31].toInt() and 0x7F).toByte() // Remove marker

        val R = RistrettoPoint.fromBytes(signature.copyOfRange(0, 32))
        val s = bytesToScalar(sBytes)

        // Reconstruct transcript
        val transcript = SigningContext("SigningContext")
        transcript.label(BIZINIKIWI_CONTEXT)
        transcript.bytes(message)

        val pubPoint = RistrettoPoint.fromBytes(publicKey)
        if (pubPoint.isZero()) return false

        transcript.protoName("Schnorr-sig")
        transcript.commitPoint("sign:pk", pubPoint)
        transcript.commitPoint("sign:R", R)

        val k = transcript.challengeScalar("sign:c")

        // Verify: R + k*P == s*G
        val left = R.add(pubPoint.multiply(k))
        val right = RistrettoPoint.BASE.multiply(s)

        return left == right
    }

    private fun decodeScalar(bytes: ByteArray): BigInteger {
        require(bytes.size == 32) { "Scalar must be 32 bytes" }
        // Little-endian
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed).mod(CURVE_ORDER)
    }

    private fun bytesToScalar(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed).mod(CURVE_ORDER)
    }

    private fun scalarToBytes(scalar: BigInteger): ByteArray {
        val bytes = scalar.toByteArray()
        val result = ByteArray(32)

        // Handle sign byte and padding
        val start = if (bytes[0] == 0.toByte() && bytes.size > 32) 1 else 0
        val length = minOf(bytes.size - start, 32)
        val offset = 32 - length

        System.arraycopy(bytes, start, result, offset, length)

        // Convert to little-endian
        return result.reversedArray()
    }

    /**
     * Strobe128 implementation for Merlin transcripts.
     */
    private class Strobe128(protocolLabel: String) {
        private val state = ByteArray(200)
        private var pos = 0
        private var posBegin = 0
        private var curFlags = 0

        init {
            // Initialize state
            state[0] = 1
            state[1] = (STROBE_R + 2).toByte()
            state[2] = 1
            state[3] = 0
            state[4] = 1
            state[5] = 96

            val strobeVersion = "STROBEv1.0.2".toByteArray(Charsets.UTF_8)
            System.arraycopy(strobeVersion, 0, state, 6, strobeVersion.size)

            keccakF1600()
            metaAD(protocolLabel.toByteArray(Charsets.UTF_8), false)
        }

        private fun keccakF1600() {
            // Keccak-f[1600] permutation
            // Using BouncyCastle's Keccak implementation would be more efficient,
            // but for now we'll use a simplified version
            val keccak = org.bouncycastle.crypto.digests.KeccakDigest(1600)
            keccak.update(state, 0, state.size)
            // This is a placeholder - need proper Keccak-p implementation
        }

        fun metaAD(data: ByteArray, more: Boolean) {
            absorb(data)
        }

        fun AD(data: ByteArray, more: Boolean) {
            absorb(data)
        }

        fun PRF(length: Int): ByteArray {
            val result = ByteArray(length)
            squeeze(result)
            return result
        }

        private fun absorb(data: ByteArray) {
            for (byte in data) {
                state[pos] = (state[pos].toInt() xor byte.toInt()).toByte()
                pos++
                if (pos == STROBE_R) {
                    runF()
                }
            }
        }

        private fun squeeze(out: ByteArray) {
            for (i in out.indices) {
                out[i] = state[pos]
                state[pos] = 0
                pos++
                if (pos == STROBE_R) {
                    runF()
                }
            }
        }

        private fun runF() {
            state[pos] = (state[pos].toInt() xor posBegin).toByte()
            state[pos + 1] = (state[pos + 1].toInt() xor 0x04).toByte()
            state[STROBE_R + 1] = (state[STROBE_R + 1].toInt() xor 0x80).toByte()
            keccakF1600()
            pos = 0
            posBegin = 0
        }
    }

    /**
     * Merlin signing context/transcript.
     */
    private class SigningContext(label: String) {
        private val strobe = Strobe128(label)

        fun label(data: ByteArray) {
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
            strobe.metaAD(lengthBytes, false)
            strobe.metaAD(data, true)
        }

        fun bytes(data: ByteArray) {
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
            strobe.metaAD(lengthBytes, false)
            strobe.AD(data, false)
        }

        fun protoName(name: String) {
            val data = name.toByteArray(Charsets.UTF_8)
            strobe.metaAD(data, false)
        }

        fun commitPoint(label: String, point: RistrettoPoint) {
            strobe.metaAD(label.toByteArray(Charsets.UTF_8), false)
            strobe.AD(point.toBytes(), false)
        }

        fun witnessScalar(label: String, nonce: ByteArray): BigInteger {
            strobe.metaAD(label.toByteArray(Charsets.UTF_8), false)
            strobe.AD(nonce, false)
            val bytes = strobe.PRF(64)
            return bytesToWideScalar(bytes)
        }

        fun challengeScalar(label: String): BigInteger {
            strobe.metaAD(label.toByteArray(Charsets.UTF_8), false)
            val bytes = strobe.PRF(64)
            return bytesToWideScalar(bytes)
        }

        private fun bytesToWideScalar(bytes: ByteArray): BigInteger {
            // Reduce 64 bytes to a scalar modulo curve order
            val reversed = bytes.reversedArray()
            return BigInteger(1, reversed).mod(CURVE_ORDER)
        }
    }

    /**
     * Ristretto255 point operations.
     * This is a placeholder - full implementation requires Ed25519 curve math.
     */
    private class RistrettoPoint private constructor(private val bytes: ByteArray) {

        companion object {
            val BASE: RistrettoPoint by lazy {
                // Ed25519 base point in Ristretto encoding
                val baseBytes = ByteArray(32)
                // TODO: Set proper base point bytes
                RistrettoPoint(baseBytes)
            }

            fun fromBytes(bytes: ByteArray): RistrettoPoint {
                require(bytes.size == 32) { "Point must be 32 bytes" }
                return RistrettoPoint(bytes.copyOf())
            }
        }

        fun toBytes(): ByteArray = bytes.copyOf()

        fun multiply(scalar: BigInteger): RistrettoPoint {
            // TODO: Implement scalar multiplication
            return this
        }

        fun add(other: RistrettoPoint): RistrettoPoint {
            // TODO: Implement point addition
            return this
        }

        fun isZero(): Boolean {
            return bytes.all { it == 0.toByte() }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is RistrettoPoint) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
