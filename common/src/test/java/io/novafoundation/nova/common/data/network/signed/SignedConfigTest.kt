package io.novafoundation.nova.common.data.network.signed

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SignedConfigTest {

    /**
     * sr25519 needs the SDK's native library, which a JVM test cannot load; Ed25519 math stands in for it here, so
     * these tests cover what this code decides -- which key, which scheme, which message -- and not schnorrkel.
     */
    private val check = SignatureCheck { _, publicKey, message, signature -> verifyEd25519(publicKey, message, signature) }

    private class Key(val scheme: String = "ed25519") {
        val private = Ed25519PrivateKeyParameters(SecureRandom())
        val public: ByteArray = private.generatePublicKey().encoded
        val id = keyIdOf(public)

        /** A wallet account signs the way the wallet's message signing does: the bytes wrapped in <Bytes>. */
        fun sign(payload: ByteArray, wrap: Boolean = scheme == "sr25519"): ByteArray {
            val message = if (wrap) "<Bytes>".toByteArray() + payload + "</Bytes>".toByteArray() else payload
            return Ed25519Signer().apply {
                init(true, private)
                update(message, 0, message.size)
            }.generateSignature()
        }
    }

    private val rootKeys = List(3) { Key("sr25519") }
    private val targetsKey = Key()
    private val now = 1_800_000_000L

    private fun envelope(payload: String, vararg signers: Key, wrap: Boolean? = null): Envelope {
        val bytes = payload.toByteArray()
        val signatures = signers.joinToString(",") {
            val sig = if (wrap == null) it.sign(bytes) else it.sign(bytes, wrap)
            """{"keyid":"${it.id}","sig":"${Base64.toBase64String(sig)}"}"""
        }
        return Envelope.parse("""{"signed":"${Base64.toBase64String(bytes)}","signatures":[$signatures]}""".toByteArray())
    }

    private fun rootPayload(version: Long, root: List<Key>, threshold: Int = 2, targets: List<Key> = listOf(targetsKey), expires: Long = now + 1000): String {
        val keys = (root + targets).distinct().joinToString(",") { """"${it.id}":{"scheme":"${it.scheme}","public":"${Base64.toBase64String(it.public)}"}""" }
        val ids = { list: List<Key> -> list.joinToString(",") { "\"${it.id}\"" } }
        return """{"type":"root","version":$version,"expires":$expires,"keys":{$keys},""" +
            """"root":{"keyids":[${ids(root)}],"threshold":$threshold},"targets":{"keyids":[${ids(targets)}],"threshold":1}}"""
    }

    private fun targetsPayload(version: Long, files: Map<String, ByteArray>, expires: Long = now + 1000): String {
        val entries = files.entries.joinToString(",") { (path, content) -> "\"$path\":\"${sha256Hex(content)}\"" }
        return """{"type":"targets","version":$version,"expires":$expires,"files":{$entries}}"""
    }

    private val bundledRoot = SignedConfigRules.trustBundledRoot(envelope(rootPayload(1, rootKeys)))

    @Test
    fun `a new root needs two of the three root keys, each counted once`() {
        val one = envelope(rootPayload(2, rootKeys), rootKeys[0])
        val sameKeyTwice = envelope(rootPayload(2, rootKeys), rootKeys[0], rootKeys[0])

        assertRejected { SignedConfigRules.nextRoot(bundledRoot, one, check) }
        assertRejected { SignedConfigRules.nextRoot(bundledRoot, sameKeyTwice, check) }
        assertEquals(2L, SignedConfigRules.nextRoot(bundledRoot, envelope(rootPayload(2, rootKeys), rootKeys[0], rootKeys[2]), check).version)
    }

    @Test
    fun `a wallet key counts only over the wrapped message, a raw key only over the bare one`() {
        val rootByBareBytes = envelope(rootPayload(2, rootKeys), rootKeys[0], rootKeys[1], wrap = false)
        val targetsByWrappedBytes = envelope(targetsPayload(5, emptyMap()), targetsKey, wrap = true)

        assertRejected { SignedConfigRules.nextRoot(bundledRoot, rootByBareBytes, check) }
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, targetsByWrappedBytes, null, now, check) }
    }

    @Test
    fun `a key of an unknown scheme is rejected`() {
        val unknown = rootPayload(1, rootKeys).replaceFirst("\"scheme\":\"sr25519\"", "\"scheme\":\"ecdsa\"")

        assertRejected { SignedConfigRules.trustBundledRoot(envelope(unknown)) }
    }

    @Test
    fun `targets signed by the targets key are trusted and checked file by file`() {
        val chains = "[1,2,3]".toByteArray()
        val targets = SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(5, mapOf("chains.json" to chains)), targetsKey), null, now, check)

        assertTrue(targets.matches("chains.json", chains))
        assertFalse(targets.matches("chains.json", "[1,2,666]".toByteArray()))
        assertFalse(targets.matches("other.json", chains))
    }

    @Test
    fun `targets signed by anyone else are rejected`() {
        val stranger = Key()

        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(5, emptyMap()), stranger), null, now, check) }
    }

    @Test
    fun `a root key is not a targets key`() {
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(5, emptyMap()), rootKeys[0]), null, now, check) }
    }

    @Test
    fun `a payload changed after signing is rejected`() {
        val signed = envelope(targetsPayload(5, emptyMap()), targetsKey)
        val tampered = Envelope(targetsPayload(6, emptyMap()).toByteArray(), signed.signatures)

        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, tampered, null, now, check) }
    }

    @Test
    fun `targets cannot go back, jump too far, or be used after they expire`() {
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(4, emptyMap()), targetsKey), 5, now, check) }
        val tooFar = 5 + SignedConfigRules.MAX_TARGETS_VERSION_JUMP + 1
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(tooFar, emptyMap()), targetsKey), 5, now, check) }
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(6, emptyMap(), expires = now), targetsKey), 5, now, check) }

        assertEquals(5L, SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(5, emptyMap()), targetsKey), 5, now, check).version)
    }

    @Test
    fun `a metadata file of the wrong type is rejected`() {
        // Well formed as targets in every field but its type: only the type check stands in the way.
        val mislabelled = targetsPayload(5, emptyMap()).replace("\"type\":\"targets\"", "\"type\":\"root\"")

        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(mislabelled, targetsKey), null, now, check) }
    }

    @Test
    fun `a new root needs the old keys and its own`() {
        val newKeys = List(3) { Key("sr25519") }

        val byOldOnly = envelope(rootPayload(2, newKeys), rootKeys[0], rootKeys[1])
        val byNewOnly = envelope(rootPayload(2, newKeys), newKeys[0], newKeys[1])
        val byBoth = envelope(rootPayload(2, newKeys), rootKeys[0], rootKeys[2], newKeys[1], newKeys[2])

        assertRejected { SignedConfigRules.nextRoot(bundledRoot, byOldOnly, check) }
        assertRejected { SignedConfigRules.nextRoot(bundledRoot, byNewOnly, check) }
        assertEquals(2L, SignedConfigRules.nextRoot(bundledRoot, byBoth, check).version)
    }

    @Test
    fun `roots move one version at a time`() {
        val skipping = envelope(rootPayload(3, rootKeys), rootKeys[0], rootKeys[1])

        assertRejected { SignedConfigRules.nextRoot(bundledRoot, skipping, check) }
    }

    @Test
    fun `a rotated root revokes the old targets key`() {
        val newTargetsKey = Key()
        val rotation = envelope(rootPayload(2, rootKeys, targets = listOf(newTargetsKey)), rootKeys[0], rootKeys[1])
        val root2 = SignedConfigRules.nextRoot(bundledRoot, rotation, check)

        assertRejected { SignedConfigRules.verifyTargets(root2, envelope(targetsPayload(6, emptyMap()), targetsKey), 5, now, check) }
        assertEquals(6L, SignedConfigRules.verifyTargets(root2, envelope(targetsPayload(6, emptyMap()), newTargetsKey), 5, now, check).version)
    }

    @Test
    fun `an expired root signs nothing`() {
        val expired = SignedConfigRules.trustBundledRoot(envelope(rootPayload(1, rootKeys, expires = now)))

        assertRejected { SignedConfigRules.verifyTargets(expired, envelope(targetsPayload(5, emptyMap()), targetsKey), null, now, check) }
    }

    @Test
    fun `a key that does not match its id is rejected`() {
        val forged = rootPayload(1, rootKeys).replace(Base64.toBase64String(rootKeys[2].public), Base64.toBase64String(Key().public))

        assertRejected { SignedConfigRules.trustBundledRoot(envelope(forged)) }
    }

    @Test
    fun `the root the APK carries is root 1 with two of three wallet keys and one targets key`() {
        // Gradle runs unit tests from the module directory.
        val root = SignedConfigRules.trustBundledRoot(Envelope.parse(java.io.File("src/main/assets/$BUNDLED_CONFIG_ROOT_ASSET").readBytes()))

        assertEquals(1L, root.version)
        assertEquals(2, root.root.threshold)
        assertEquals(List(3) { KeyScheme.SR25519 }, root.root.keyIds.map { root.keys.getValue(it).scheme })
        assertEquals(listOf("6066e6a8729c340989e7c150efbd63b1efb9ff1ccaa1e1f38fa015c2f1f9ab5a"), root.targets.keyIds.toList())
        assertTrue(root.expiresAt > 1_950_000_000L)
    }

    @Test
    fun `garbage is rejected, not thrown as something else`() {
        assertRejected { Envelope.parse("not json".toByteArray()) }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (e: SignedConfigException) {
            return
        }
        throw AssertionError("expected SignedConfigException")
    }
}
