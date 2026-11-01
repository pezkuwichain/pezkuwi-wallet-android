package io.novafoundation.nova.common.data.network.signed

import io.novasama.substrate_sdk_android.encrypt.SignatureVerifier
import io.novasama.substrate_sdk_android.encrypt.SignatureWrapper
import io.novasama.substrate_sdk_android.encrypt.Signer

/** Ed25519 through BouncyCastle; sr25519 through the SDK's native schnorrkel, as the wallet signs with it. */
object SubstrateSignatureCheck : SignatureCheck {

    override fun verify(scheme: KeyScheme, publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = when (scheme) {
        KeyScheme.ED25519 -> verifyEd25519(publicKey, message, signature)
        // sr25519 signs the whole message; the hashing argument is only read for ECDSA.
        KeyScheme.SR25519 -> SignatureVerifier.verify(SignatureWrapper.Sr25519(signature), Signer.MessageHashing.SUBSTRATE, message, publicKey)
    }
}
