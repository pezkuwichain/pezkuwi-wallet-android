package io.novafoundation.nova.feature_account_impl

import android.security.keystore.KeyGenParameterSpec
import android.util.Base64
import android.security.keystore.KeyProperties
import io.novafoundation.nova.common.data.storage.Preferences
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A keystore key usable only after a strong biometric check. Turning biometry on seals a random token with
 * it; each unlock must open that token, so a reported success counts only when the key itself was usable.
 */
class BiometricUnlockKey(private val preferences: Preferences) {

    /**
     * The cipher for the next prompt: it opens the stored token, or seals a new one when there is none.
     *
     * @throws android.security.keystore.KeyPermanentlyInvalidatedException after biometrics were enrolled or removed
     */
    fun cipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val sealed = storedToken()

        if (sealed == null) {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } else {
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
        }

        return cipher
    }

    /** Runs [cipher] after the prompt succeeded; false when the key refused it. */
    fun complete(cipher: Cipher): Boolean = runCatching {
        val sealed = storedToken()

        if (sealed == null) {
            val token = ByteArray(TOKEN_SIZE).also(SecureRandom()::nextBytes)
            preferences.putString(TOKEN_FIELD, Base64.encodeToString(cipher.iv + cipher.doFinal(token), Base64.NO_WRAP))
        } else {
            cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
        }
    }.isSuccess

    fun clear() {
        preferences.removeField(TOKEN_FIELD)
        keyStore().deleteEntry(ALIAS)
    }

    private fun storedToken(): ByteArray? = preferences.getString(TOKEN_FIELD)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as SecretKey?)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun keyStore() = KeyStore.getInstance(KEY_STORE).apply { load(null) }

    private companion object {
        const val KEY_STORE = "AndroidKeyStore"
        const val ALIAS = "biometric_unlock_v1"
        const val TOKEN_FIELD = "biometric_unlock_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val TOKEN_SIZE = 32
    }
}
