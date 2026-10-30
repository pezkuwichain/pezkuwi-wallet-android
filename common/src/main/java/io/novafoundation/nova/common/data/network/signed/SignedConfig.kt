package io.novafoundation.nova.common.data.network.signed

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import java.security.MessageDigest

/**
 * Signed config metadata, after TUF: a root, signed by a threshold of root keys, names the keys that may sign the
 * targets list and how many must; the targets list gives the sha256 of every config file. The APK carries a first
 * root. A new root is accepted only one version at a time and only when a threshold of the old root's keys and of
 * its own keys signed it, so keys can be replaced without an app release and a stolen targets key can be revoked.
 *
 * Every metadata file is an envelope: `{"signed": base64(payload), "signatures": [{"keyid", "sig"}]}`, where the
 * signature is Ed25519 over the payload bytes exactly as published and keyid is hex sha256 of the 32-byte key.
 */
class SignedConfigException(message: String) : Exception(message)

class Envelope(val payload: ByteArray, val signatures: List<Pair<String, ByteArray>>) {

    companion object {

        fun parse(bytes: ByteArray): Envelope = guard {
            val json = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            val signatures = json.getAsJsonArray("signatures").map {
                val signature = it.asJsonObject
                signature["keyid"].asString to Base64.decode(signature["sig"].asString)
            }

            Envelope(Base64.decode(json["signed"].asString), signatures)
        }
    }

    /** How many of [keyIds] signed the payload; a key signing twice counts once, a key not in [keys] not at all. */
    fun validSignatures(keys: Map<String, ByteArray>, keyIds: Set<String>): Int {
        return signatures
            .filter { (keyId, signature) -> keyId in keyIds && keys[keyId]?.let { verifyEd25519(it, payload, signature) } == true }
            .map { (keyId, _) -> keyId }
            .toSet()
            .size
    }

    fun payloadJson(expectedType: String): JsonObject = guard {
        val json = JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject
        if (json["type"].asString != expectedType) throw SignedConfigException("expected $expectedType, got ${json["type"]}")
        json
    }
}

class Role(val keyIds: Set<String>, val threshold: Int)

class RootMetadata(
    val version: Long,
    val expiresAt: Long,
    val keys: Map<String, ByteArray>,
    val root: Role,
    val targets: Role,
) {

    companion object {

        fun parse(envelope: Envelope): RootMetadata = guard {
            val json = envelope.payloadJson("root")
            val keys = json.getAsJsonObject("keys").entrySet().associate { (keyId, key) ->
                val bytes = Base64.decode(key.asString)
                if (bytes.size != 32 || keyIdOf(bytes) != keyId) throw SignedConfigException("key $keyId does not match its id")
                keyId to bytes
            }

            RootMetadata(
                version = json["version"].asLong,
                expiresAt = json["expires"].asLong,
                keys = keys,
                root = parseRole(json.getAsJsonObject("root"), keys),
                targets = parseRole(json.getAsJsonObject("targets"), keys),
            )
        }

        private fun parseRole(json: JsonObject, keys: Map<String, ByteArray>): Role {
            val keyIds = json.getAsJsonArray("keyids").map { it.asString }.toSet()
            val threshold = json["threshold"].asInt
            if (threshold < 1 || threshold > keyIds.size || !keys.keys.containsAll(keyIds)) throw SignedConfigException("unusable role")
            return Role(keyIds, threshold)
        }
    }
}

class TargetsMetadata(val version: Long, val expiresAt: Long, val files: Map<String, String>) {

    companion object {

        fun parse(envelope: Envelope): TargetsMetadata = guard {
            val json = envelope.payloadJson("targets")
            TargetsMetadata(
                version = json["version"].asLong,
                expiresAt = json["expires"].asLong,
                files = json.getAsJsonObject("files").entrySet().associate { (path, hash) -> path to hash.asString.lowercase() },
            )
        }
    }

    fun matches(path: String, content: ByteArray): Boolean = files[path] == sha256Hex(content)
}

object SignedConfigRules {

    /** A targets version may move forward by at most this much at once, so a stolen key cannot freeze updates. */
    const val MAX_TARGETS_VERSION_JUMP = 10_000L

    /** The APK's own root: it must be signed by a threshold of its own root keys. */
    fun trustBundledRoot(envelope: Envelope): RootMetadata {
        val root = RootMetadata.parse(envelope)
        requireThreshold(envelope, root.keys, root.root, "bundled root")
        return root
    }

    /** The root that follows [trusted]; expiry is not checked here, so an expired root can still be rotated out. */
    fun nextRoot(trusted: RootMetadata, envelope: Envelope): RootMetadata {
        val next = RootMetadata.parse(envelope)
        if (next.version != trusted.version + 1) throw SignedConfigException("root ${next.version} does not follow ${trusted.version}")
        requireThreshold(envelope, trusted.keys, trusted.root, "root ${next.version} by the previous root")
        requireThreshold(envelope, next.keys, next.root, "root ${next.version} by itself")
        return next
    }

    /**
     * [now] is seconds since the epoch; callers pass the latest of the device clock and anything already trusted,
     * so turning the clock back does not revive an expired list.
     */
    fun verifyTargets(root: RootMetadata, envelope: Envelope, lastTrustedVersion: Long?, now: Long): TargetsMetadata {
        if (root.expiresAt <= now) throw SignedConfigException("root ${root.version} expired")
        requireThreshold(envelope, root.keys, root.targets, "targets")

        val targets = TargetsMetadata.parse(envelope)
        if (targets.expiresAt <= now) throw SignedConfigException("targets ${targets.version} expired")
        if (lastTrustedVersion != null) {
            if (targets.version < lastTrustedVersion) throw SignedConfigException("targets ${targets.version} is older than $lastTrustedVersion")
            if (targets.version - lastTrustedVersion > MAX_TARGETS_VERSION_JUMP) throw SignedConfigException("targets ${targets.version} jumps too far")
        }
        return targets
    }

    private fun requireThreshold(envelope: Envelope, keys: Map<String, ByteArray>, role: Role, what: String) {
        val valid = envelope.validSignatures(keys, role.keyIds)
        if (valid < role.threshold) throw SignedConfigException("$what: $valid of ${role.threshold} required signatures")
    }
}

fun keyIdOf(publicKey: ByteArray): String = sha256Hex(publicKey)

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
    Ed25519Signer().apply {
        init(false, Ed25519PublicKeyParameters(publicKey, 0))
        update(message, 0, message.size)
    }.verifySignature(signature)
}.getOrDefault(false)

private inline fun <T> guard(block: () -> T): T = try {
    block()
} catch (e: SignedConfigException) {
    throw e
} catch (e: Exception) {
    throw SignedConfigException("malformed metadata: $e")
}
