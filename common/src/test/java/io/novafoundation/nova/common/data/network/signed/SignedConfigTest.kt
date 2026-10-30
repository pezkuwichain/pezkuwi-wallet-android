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

    private class Key {
        val private = Ed25519PrivateKeyParameters(SecureRandom())
        val public: ByteArray = private.generatePublicKey().encoded
        val id = keyIdOf(public)

        fun sign(payload: ByteArray): ByteArray = Ed25519Signer().apply {
            init(true, private)
            update(payload, 0, payload.size)
        }.generateSignature()
    }

    private val rootKeys = List(3) { Key() }
    private val targetsKey = Key()
    private val now = 1_800_000_000L

    private fun envelope(payload: String, vararg signers: Key): Envelope {
        val bytes = payload.toByteArray()
        val signatures = signers.joinToString(",") { """{"keyid":"${it.id}","sig":"${Base64.toBase64String(it.sign(bytes))}"}""" }
        return Envelope.parse("""{"signed":"${Base64.toBase64String(bytes)}","signatures":[$signatures]}""".toByteArray())
    }

    private fun rootPayload(version: Long, root: List<Key>, threshold: Int = 2, targets: List<Key> = listOf(targetsKey), expires: Long = now + 1000): String {
        val keys = (root + targets).distinct().joinToString(",") { """"${it.id}":"${Base64.toBase64String(it.public)}"""" }
        val ids = { list: List<Key> -> list.joinToString(",") { "\"${it.id}\"" } }
        return """{"type":"root","version":$version,"expires":$expires,"keys":{$keys},""" +
            """"root":{"keyids":[${ids(root)}],"threshold":$threshold},"targets":{"keyids":[${ids(targets)}],"threshold":1}}"""
    }

    private fun targetsPayload(version: Long, files: Map<String, ByteArray>, expires: Long = now + 1000): String {
        val entries = files.entries.joinToString(",") { (path, content) -> "\"$path\":\"${sha256Hex(content)}\"" }
        return """{"type":"targets","version":$version,"expires":$expires,"files":{$entries}}"""
    }

    private val bundledRoot = SignedConfigRules.trustBundledRoot(envelope(rootPayload(1, rootKeys), rootKeys[0], rootKeys[1]))

    @Test
    fun `a root needs two of its three keys`() {
        val one = envelope(rootPayload(1, rootKeys), rootKeys[0])
        val sameKeyTwice = envelope(rootPayload(1, rootKeys), rootKeys[0], rootKeys[0])

        assertRejected { SignedConfigRules.trustBundledRoot(one) }
        assertRejected { SignedConfigRules.trustBundledRoot(sameKeyTwice) }
    }

    @Test
    fun `targets signed by the targets key are trusted and checked file by file`() {
        val chains = "[1,2,3]".toByteArray()
        val targets = SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(5, mapOf("chains.json" to chains)), targetsKey), null, now)

        assertTrue(targets.matches("chains.json", chains))
        assertFalse(targets.matches("chains.json", "[1,2,666]".toByteArray()))
        assertFalse(targets.matches("other.json", chains))
    }

    @Test
    fun `targets signed by anyone else are rejected`() {
        val stranger = Key()

        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(5, emptyMap()), stranger), null, now) }
    }

    @Test
    fun `a payload changed after signing is rejected`() {
        val signed = envelope(targetsPayload(5, emptyMap()), targetsKey)
        val tampered = Envelope(targetsPayload(6, emptyMap()).toByteArray(), signed.signatures)

        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, tampered, null, now) }
    }

    @Test
    fun `targets cannot go back, jump too far, or be used after they expire`() {
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(4, emptyMap()), targetsKey), 5, now) }
        val tooFar = 5 + SignedConfigRules.MAX_TARGETS_VERSION_JUMP + 1
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(tooFar, emptyMap()), targetsKey), 5, now) }
        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(6, emptyMap(), expires = now), targetsKey), 5, now) }

        assertEquals(5L, SignedConfigRules.verifyTargets(bundledRoot, envelope(targetsPayload(5, emptyMap()), targetsKey), 5, now).version)
    }

    @Test
    fun `a metadata file of the wrong type is rejected`() {
        // Well formed as targets in every field but its type: only the type check stands in the way.
        val mislabelled = targetsPayload(5, emptyMap()).replace("\"type\":\"targets\"", "\"type\":\"root\"")

        assertRejected { SignedConfigRules.verifyTargets(bundledRoot, envelope(mislabelled, targetsKey), null, now) }
    }

    @Test
    fun `a new root needs the old keys and its own`() {
        val newKeys = List(3) { Key() }

        val byOldOnly = envelope(rootPayload(2, newKeys), rootKeys[0], rootKeys[1])
        val byNewOnly = envelope(rootPayload(2, newKeys), newKeys[0], newKeys[1])
        val byBoth = envelope(rootPayload(2, newKeys), rootKeys[0], rootKeys[2], newKeys[1], newKeys[2])

        assertRejected { SignedConfigRules.nextRoot(bundledRoot, byOldOnly) }
        assertRejected { SignedConfigRules.nextRoot(bundledRoot, byNewOnly) }
        assertEquals(2L, SignedConfigRules.nextRoot(bundledRoot, byBoth).version)
    }

    @Test
    fun `roots move one version at a time`() {
        val skipping = envelope(rootPayload(3, rootKeys), rootKeys[0], rootKeys[1])

        assertRejected { SignedConfigRules.nextRoot(bundledRoot, skipping) }
    }

    @Test
    fun `a rotated root revokes the old targets key`() {
        val newTargetsKey = Key()
        val root2 = SignedConfigRules.nextRoot(bundledRoot, envelope(rootPayload(2, rootKeys, targets = listOf(newTargetsKey)), rootKeys[0], rootKeys[1]))

        assertRejected { SignedConfigRules.verifyTargets(root2, envelope(targetsPayload(6, emptyMap()), targetsKey), 5, now) }
        assertEquals(6L, SignedConfigRules.verifyTargets(root2, envelope(targetsPayload(6, emptyMap()), newTargetsKey), 5, now).version)
    }

    @Test
    fun `an expired root signs nothing`() {
        val expired = SignedConfigRules.trustBundledRoot(envelope(rootPayload(1, rootKeys, expires = now), rootKeys[0], rootKeys[1]))

        assertRejected { SignedConfigRules.verifyTargets(expired, envelope(targetsPayload(5, emptyMap()), targetsKey), null, now) }
    }

    @Test
    fun `a key that does not match its id is rejected`() {
        val forged = rootPayload(1, rootKeys).replace(Base64.toBase64String(rootKeys[2].public), Base64.toBase64String(Key().public))

        assertRejected { SignedConfigRules.trustBundledRoot(envelope(forged, rootKeys[0], rootKeys[1])) }
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
