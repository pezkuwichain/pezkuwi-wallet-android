package io.novafoundation.nova.common.data.storage.encrypt

import org.bouncycastle.util.encoders.Base64

/**
 * Text form of encrypted preference values. Current values are `v2:` and Base64 of [current]'s output;
 * anything else is the legacy scheme and is read through [legacy].
 */
class EncryptionUtil(
    private val current: ValueCipher,
    private val legacy: ValueDecrypter,
) {

    fun encrypt(cleartext: String): String {
        return CURRENT_PREFIX + Base64.toBase64String(current.encrypt(cleartext.toByteArray()))
    }

    /** @throws UndecryptableValueException */
    fun decrypt(stored: String): String {
        val clear = when {
            isCurrent(stored) -> current.decrypt(decodeBase64(stored.removePrefix(CURRENT_PREFIX)))
            // The legacy scheme stored an empty string as is
            stored.isEmpty() -> ByteArray(0)
            else -> legacy.decrypt(decodeBase64(stored))
        }

        return String(clear)
    }

    fun isCurrent(stored: String): Boolean = stored.startsWith(CURRENT_PREFIX)

    private fun decodeBase64(text: String): ByteArray = try {
        Base64.decode(text)
    } catch (e: Exception) {
        throw UndecryptableValueException(e)
    }

    private companion object {
        const val CURRENT_PREFIX = "v2:"
    }
}
