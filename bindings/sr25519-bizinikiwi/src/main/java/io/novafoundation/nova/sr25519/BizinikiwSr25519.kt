package io.novafoundation.nova.sr25519

/**
 * SR25519 signing implementation for PezkuwiChain using "bizinikiwi" signing context.
 *
 * Standard Substrate chains use "substrate" context, but Pezkuwi ecosystem uses "bizinikiwi".
 * This native library provides signing compatible with @pezkuwi/scure-sr25519.
 */
object BizinikiwSr25519 {

    init {
        System.loadLibrary("sr25519_bizinikiwi_java")
    }

    /**
     * Sign a message using SR25519 with bizinikiwi context.
     *
     * @param publicKey 32-byte public key
     * @param secretKey 64-byte secret key (32-byte scalar + 32-byte nonce)
     * @param message Message bytes to sign
     * @return 64-byte signature
     */
    external fun sign(publicKey: ByteArray, secretKey: ByteArray, message: ByteArray): ByteArray

    /**
     * Verify a signature using bizinikiwi context.
     *
     * @param signature 64-byte signature
     * @param message Original message bytes
     * @param publicKey 32-byte public key
     * @return true if signature is valid
     */
    external fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    /**
     * Generate a keypair from a 32-byte seed.
     *
     * @param seed 32-byte seed (mini secret key)
     * @return 96-byte keypair (32 key + 32 nonce + 32 public)
     */
    external fun keypairFromSeed(seed: ByteArray): ByteArray

    /**
     * Extract public key from keypair.
     *
     * @param keypair 96-byte keypair
     * @return 32-byte public key
     */
    external fun publicKeyFromKeypair(keypair: ByteArray): ByteArray

    /**
     * Extract secret key from keypair.
     *
     * @param keypair 96-byte keypair
     * @return 64-byte secret key
     */
    external fun secretKeyFromKeypair(keypair: ByteArray): ByteArray
}
