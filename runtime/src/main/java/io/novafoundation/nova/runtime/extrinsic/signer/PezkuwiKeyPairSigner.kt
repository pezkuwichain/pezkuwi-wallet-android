package io.novafoundation.nova.runtime.extrinsic.signer

import android.util.Log
import io.novafoundation.nova.runtime.BuildConfig
import io.novafoundation.nova.sr25519.BizinikiwSr25519
import io.novasama.substrate_sdk_android.encrypt.SignatureWrapper
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignedRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.InheritedImplication
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.verifySignature.GeneralTransactionSigner
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.signingPayload

/**
 * SR25519 signer for Pezkuwi chains using "bizinikiwi" signing context.
 *
 * This signer is used instead of the standard KeyPairSigner for Pezkuwi ecosystem chains
 * (Pezkuwi, Pezkuwi Asset Hub, Pezkuwi People) which require signatures with "bizinikiwi"
 * context instead of the standard "substrate" context used by Polkadot ecosystem chains.
 */
class PezkuwiKeyPairSigner private constructor(
    private val secretKey: ByteArray,
    private val publicKey: ByteArray
) : GeneralTransactionSigner {

    companion object {
        /**
         * Create a PezkuwiKeyPairSigner from a 32-byte seed.
         * The seed is expanded to a full keypair using BizinikiwSr25519.
         */
        fun fromSeed(seed: ByteArray): PezkuwiKeyPairSigner {
            require(seed.size == 32) { "Seed must be 32 bytes, got ${seed.size}" }

            if (BuildConfig.DEBUG) Log.d("PezkuwiSigner", "Creating signer from seed")

            // Expand seed to 96-byte keypair
            val expandedKeypair = BizinikiwSr25519.keypairFromSeed(seed)
            if (BuildConfig.DEBUG) Log.d("PezkuwiSigner", "Expanded keypair size: ${expandedKeypair.size}")

            // Extract 64-byte secret key and 32-byte public key
            val secretKey = BizinikiwSr25519.secretKeyFromKeypair(expandedKeypair)
            val publicKey = BizinikiwSr25519.publicKeyFromKeypair(expandedKeypair)

            if (BuildConfig.DEBUG) Log.d("PezkuwiSigner", "Secret key size: ${secretKey.size}")

            return PezkuwiKeyPairSigner(secretKey, publicKey)
        }
    }

    override suspend fun signInheritedImplication(
        inheritedImplication: InheritedImplication,
        accountId: AccountId
    ): SignatureWrapper {
        val payload = inheritedImplication.signingPayload()

        if (BuildConfig.DEBUG) {
            Log.d("PezkuwiSigner", "=== SIGNING WITH BIZINIKIWI ===")
            Log.d("PezkuwiSigner", "Payload size: ${payload.size}")
        }

        // Use BizinikiwSr25519 native library with "bizinikiwi" signing context
        val signature = BizinikiwSr25519.sign(
            publicKey = publicKey,
            secretKey = secretKey,
            message = payload
        )

        // Verify locally
        val verified = BizinikiwSr25519.verify(signature, payload, publicKey)
        if (BuildConfig.DEBUG) Log.d("PezkuwiSigner", "Local verification: $verified")

        return SignatureWrapper.Sr25519(signature)
    }

    suspend fun signRaw(payload: SignerPayloadRaw): SignedRaw {
        // Use BizinikiwSr25519 native library with "bizinikiwi" signing context
        val signature = BizinikiwSr25519.sign(
            publicKey = publicKey,
            secretKey = secretKey,
            message = payload.message
        )

        return SignedRaw(payload, SignatureWrapper.Sr25519(signature))
    }
}
