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
 * root, trusted because the APK is. A new root is accepted only one version at a time and only when a threshold of
 * the old root's keys and of its own keys signed it, so keys can be replaced without an app release and a stolen
 * targets key can be revoked.
 *
 * Every metadata file is an envelope: `{"signed": base64(payload), "signatures": [{"keyid", "sig"}]}`; keyid is the
 * hex sha256 of the 32-byte key. An ed25519 key signs the payload bytes exactly as published. An sr25519 key is a
 * person's wallet account, and a wallet signs a message as `<Bytes>payload</Bytes>`, so that is what is verified.
 */
class SignedConfigException(message: String) : Exception(message)

enum class KeyScheme { ED25519, SR25519 }

class PublicKey(val scheme: KeyScheme, val bytes: ByteArray)

/** Checks one signature over exactly [message]; the envelope decides what the message is. */
fun interface SignatureCheck {

    fun verify(scheme: KeyScheme, publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

class Envelope(val payload: ByteArray, val signatures: List<Pair<String, ByteArray>>) {

    companion object {

        fun parse(bytes: ByteArray): Envelope = guard {
            val json = parseJson(String(bytes, Charsets.UTF_8)).asJsonObject
            val signatures = json.getAsJsonArray("signatures").map {
                val signature = it.asJsonObject
                signature["keyid"].asString to Base64.decode(signature["sig"].asString)
            }

            Envelope(Base64.decode(json["signed"].asString), signatures)
        }
    }

    /** How many of [keyIds] signed the payload; a key signing twice counts once, a key not in [keys] not at all. */
    fun validSignatures(keys: Map<String, PublicKey>, keyIds: Set<String>, check: SignatureCheck): Int {
        return signatures
            .filter { (keyId, signature) ->
                val key = keys[keyId]
                keyId in keyIds && key != null && runCatching { check.verify(key.scheme, key.bytes, signedMessage(key.scheme), signature) }.getOrDefault(false)
            }
            .map { (keyId, _) -> keyId }
            .toSet()
            .size
    }

    private fun signedMessage(scheme: KeyScheme): ByteArray = when (scheme) {
        KeyScheme.ED25519 -> payload
        KeyScheme.SR25519 -> "<Bytes>".toByteArray() + payload + "</Bytes>".toByteArray()
    }

    fun payloadJson(expectedType: String): JsonObject = guard {
        val json = parseJson(String(payload, Charsets.UTF_8)).asJsonObject
        if (json["type"].asString != expectedType) throw SignedConfigException("expected $expectedType, got ${json["type"]}")
        json
    }
}

class Role(val keyIds: Set<String>, val threshold: Int)

class RootMetadata(
    val version: Long,
    val expiresAt: Long,
    val keys: Map<String, PublicKey>,
    val root: Role,
    val targets: Role,
) {

    companion object {

        fun parse(envelope: Envelope): RootMetadata = guard {
            val json = envelope.payloadJson("root")
            val keys = json.getAsJsonObject("keys").entrySet().associate { (keyId, value) ->
                val key = value.asJsonObject
                val scheme = when (key["scheme"].asString) {
                    "ed25519" -> KeyScheme.ED25519
                    "sr25519" -> KeyScheme.SR25519
                    else -> throw SignedConfigException("key $keyId has unknown scheme ${key["scheme"]}")
                }
                val bytes = Base64.decode(key["public"].asString)
                if (bytes.size != 32 || keyIdOf(bytes) != keyId) throw SignedConfigException("key $keyId does not match its id")
                keyId to PublicKey(scheme, bytes)
            }

            RootMetadata(
                version = json["version"].asLong,
                expiresAt = json["expires"].asLong,
                keys = keys,
                root = parseRole(json.getAsJsonObject("root"), keys),
                targets = parseRole(json.getAsJsonObject("targets"), keys),
            )
        }

        private fun parseRole(json: JsonObject, keys: Map<String, PublicKey>): Role {
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

    /** The APK's own root, trusted as the APK is: its signatures are not what makes it trustworthy. */
    fun trustBundledRoot(envelope: Envelope): RootMetadata = RootMetadata.parse(envelope)

    /** The root that follows [trusted]; expiry is not checked here, so an expired root can still be rotated out. */
    fun nextRoot(trusted: RootMetadata, envelope: Envelope, check: SignatureCheck): RootMetadata {
        val next = RootMetadata.parse(envelope)
        if (next.version != trusted.version + 1) throw SignedConfigException("root ${next.version} does not follow ${trusted.version}")
        requireThreshold(envelope, trusted.keys, trusted.root, check, "root ${next.version} by the previous root")
        requireThreshold(envelope, next.keys, next.root, check, "root ${next.version} by itself")
        return next
    }

    /**
     * [now] is seconds since the epoch; callers pass the latest of the device clock and anything already trusted,
     * so turning the clock back does not revive an expired list.
     */
    fun verifyTargets(root: RootMetadata, envelope: Envelope, lastTrustedVersion: Long?, now: Long, check: SignatureCheck): TargetsMetadata {
        if (root.expiresAt <= now) throw SignedConfigException("root ${root.version} expired")
        requireThreshold(envelope, root.keys, root.targets, check, "targets")

        val targets = TargetsMetadata.parse(envelope)
        if (targets.expiresAt <= now) throw SignedConfigException("targets ${targets.version} expired")
        if (lastTrustedVersion != null) {
            if (targets.version < lastTrustedVersion) throw SignedConfigException("targets ${targets.version} is older than $lastTrustedVersion")
            if (targets.version - lastTrustedVersion > MAX_TARGETS_VERSION_JUMP) throw SignedConfigException("targets ${targets.version} jumps too far")
        }
        return targets
    }

    private fun requireThreshold(envelope: Envelope, keys: Map<String, PublicKey>, role: Role, check: SignatureCheck, what: String) {
        val valid = envelope.validSignatures(keys, role.keyIds, check)
        if (valid < role.threshold) throw SignedConfigException("$what: $valid of ${role.threshold} required signatures")
    }
}

fun keyIdOf(publicKey: ByteArray): String = sha256Hex(publicKey)

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
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

// The Gson the app resolves predates JsonParser.parseString.
@Suppress("DEPRECATION")
private fun parseJson(text: String) = JsonParser().parse(text)
