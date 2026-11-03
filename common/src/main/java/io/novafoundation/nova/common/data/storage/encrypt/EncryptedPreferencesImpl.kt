package io.novafoundation.nova.common.data.storage.encrypt

import android.util.Log
import io.novafoundation.nova.common.data.storage.Preferences

class EncryptedPreferencesImpl(
    private val preferences: Preferences,
    private val encryptionUtil: EncryptionUtil,
    private val logError: (String, Throwable?) -> Unit = { message, error -> Log.e(LOG_TAG, message, error) },
) : EncryptedPreferences {

    override fun putEncryptedString(field: String, value: String) {
        preferences.putString(field, encryptionUtil.encrypt(value))
    }

    /**
     * A value that cannot be decrypted reads as absent and stays stored as it is. A legacy value is
     * re-encrypted in the current scheme, and replaced only once the new copy decrypts to the same text.
     */
    override fun getDecryptedString(field: String): String? {
        val stored = preferences.getString(field) ?: return null

        val value = try {
            encryptionUtil.decrypt(stored)
        } catch (e: UndecryptableValueException) {
            logError("Cannot decrypt $field", e)
            return null
        }

        if (!encryptionUtil.isCurrent(stored)) {
            reEncrypt(field, value)
        }

        return value
    }

    override fun hasKey(field: String): Boolean {
        return preferences.contains(field)
    }

    override fun removeKey(field: String) {
        preferences.removeField(field)
    }

    private fun reEncrypt(field: String, value: String) {
        val reEncrypted = try {
            encryptionUtil.encrypt(value).takeIf { encryptionUtil.decrypt(it) == value }
        } catch (e: Exception) {
            logError("Cannot re-encrypt $field", e)
            return
        }

        if (reEncrypted != null) {
            preferences.putString(field, reEncrypted)
        } else {
            logError("Re-encrypted $field does not decrypt to its value", null)
        }
    }

    private companion object {
        const val LOG_TAG = "EncryptedPreferences"
    }
}
