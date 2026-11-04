package io.novafoundation.nova

import io.novafoundation.nova.common.data.network.signed.KeyScheme
import io.novafoundation.nova.common.data.network.signed.SubstrateSignatureCheck
import io.novasama.substrate_sdk_android.extensions.fromHex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root signing page and the utils root check count an sr25519 root signature only when it verifies under the
 * "substrate" signing context, because that is what this check accepts. sr25519 runs in native code, so only a
 * device can confirm it. Vectors: the public development phrase "bottom drive obey lake curtain smoke basket hold
 * race lonely fit walk" (no derivation), signed with @polkadot/util-crypto (substrate) and @pezkuwi/util-crypto
 * (bizinikiwi) over the message below.
 */
class SignedConfigSignatureVectorTest {

    private val publicKey = "0x46ebddef8cd9bb167dc30878d7113b7e168e6f0646beffd77d69d39bad76b47a".fromHex()
    private val message = "<Bytes>pezkuwi signed config test vector</Bytes>".toByteArray()

    private val substrateSignature = ("0x52bc9e50674838cf8dd06347715eed0047c1b6168fdbb9c19a272582a0bef41c" +
        "f5eec26341b077331ca29bbb9cb3549af9e43b0ad6be722590c426502c068f83").fromHex()
    private val bizinikiwiSignature = ("0x1a26e728fab052cf48c6bfd3762ba6221fb118f1efc2e659caefb015a0ee9734" +
        "d1a50d92e1e61a09285a399627e94085d3c941b4dee055ddc60be16f97d40987").fromHex()

    @Test
    fun aSubstrateContextSignatureIsAccepted() {
        assertTrue(SubstrateSignatureCheck.verify(KeyScheme.SR25519, publicKey, message, substrateSignature))
    }

    @Test
    fun aBizinikiwiContextSignatureIsNotAccepted() {
        assertFalse(SubstrateSignatureCheck.verify(KeyScheme.SR25519, publicKey, message, bizinikiwiSignature))
    }

    @Test
    fun aSignatureOverOtherBytesIsNotAccepted() {
        assertFalse(SubstrateSignatureCheck.verify(KeyScheme.SR25519, publicKey, message + 0.toByte(), substrateSignature))
    }
}
