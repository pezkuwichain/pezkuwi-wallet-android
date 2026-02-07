package io.novafoundation.nova.runtime.extrinsic.signer

import io.novafoundation.nova.sr25519.BizinikiwSr25519
import io.novasama.substrate_sdk_android.encrypt.SignatureWrapper
import io.novasama.substrate_sdk_android.encrypt.keypair.Keypair
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
class PezkuwiKeyPairSigner(
    private val keypair: Keypair
) : GeneralTransactionSigner {

    override suspend fun signInheritedImplication(
        inheritedImplication: InheritedImplication,
        accountId: AccountId
    ): SignatureWrapper {
        val payload = inheritedImplication.signingPayload()

        // Use BizinikiwSr25519 native library with "bizinikiwi" signing context
        val signature = BizinikiwSr25519.sign(
            publicKey = keypair.publicKey,
            secretKey = keypair.privateKey,
            message = payload
        )

        return SignatureWrapper.Sr25519(signature)
    }

    suspend fun signRaw(payload: SignerPayloadRaw): SignedRaw {
        // Use BizinikiwSr25519 native library with "bizinikiwi" signing context
        val signature = BizinikiwSr25519.sign(
            publicKey = keypair.publicKey,
            secretKey = keypair.privateKey,
            message = payload.message
        )

        return SignedRaw(payload, SignatureWrapper.Sr25519(signature))
    }
}
