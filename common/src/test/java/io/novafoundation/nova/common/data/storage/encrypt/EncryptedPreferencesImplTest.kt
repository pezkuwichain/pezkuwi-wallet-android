package io.novafoundation.nova.common.data.storage.encrypt

import io.novafoundation.nova.common.data.storage.Preferences
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedPreferencesImplTest {

    private val preferences = MapPreferences()
    private val legacy = JvmAesCbc()
    private val errors = mutableListOf<String>()

    private fun encryptedPreferences(current: ValueCipher = JvmAesGcm()) =
        EncryptedPreferencesImpl(preferences, EncryptionUtil(current, legacy), logError = { message, _ -> errors += message })

    @Test
    fun `a legacy value is read and re-encrypted in the current scheme`() {
        preferences.putString(FIELD, legacy.encryptToText("seed words"))
        val encryptedPreferences = encryptedPreferences()

        assertEquals("seed words", encryptedPreferences.getDecryptedString(FIELD))
        assertTrue(preferences.getString(FIELD)!!.startsWith("v2:"))
        assertEquals("seed words", encryptedPreferences.getDecryptedString(FIELD))
    }

    @Test
    fun `a legacy empty string stays empty`() {
        preferences.putString(FIELD, "")

        assertEquals("", encryptedPreferences().getDecryptedString(FIELD))
        assertTrue(preferences.getString(FIELD)!!.startsWith("v2:"))
    }

    @Test
    fun `an undecryptable legacy value reads as absent and is left as it is`() {
        val corrupt = Base64.toBase64String(ByteArray(48) { 7 })
        preferences.putString(FIELD, corrupt)

        assertNull(encryptedPreferences().getDecryptedString(FIELD))
        assertEquals(corrupt, preferences.getString(FIELD))
        assertEquals(1, errors.size)
    }

    @Test
    fun `a tampered current value reads as absent`() {
        val encryptedPreferences = encryptedPreferences()
        encryptedPreferences.putEncryptedString(FIELD, "1234")
        val stored = preferences.getString(FIELD)!!
        val bytes = Base64.decode(stored.removePrefix("v2:")).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        preferences.putString(FIELD, "v2:" + Base64.toBase64String(bytes))

        assertNull(encryptedPreferences.getDecryptedString(FIELD))
    }

    @Test
    fun `a legacy value is kept when its re-encryption does not decrypt back`() {
        val legacyText = legacy.encryptToText("seed words")
        preferences.putString(FIELD, legacyText)

        assertEquals("seed words", encryptedPreferences(current = GarblingCipher()).getDecryptedString(FIELD))
        assertEquals(legacyText, preferences.getString(FIELD))
        assertEquals(1, errors.size)
    }

    @Test
    fun `values are written in the current scheme`() {
        encryptedPreferences().putEncryptedString(FIELD, "1234")

        assertTrue(preferences.getString(FIELD)!!.startsWith("v2:"))
    }

    private class MapPreferences : Preferences by Mockito.mock(Preferences::class.java) {
        private val values = mutableMapOf<String, String?>()

        override fun contains(field: String) = field in values
        override fun putString(field: String, value: String?) = values.set(field, value)
        override fun getString(field: String): String? = values[field]
        override fun removeField(field: String) {
            values.remove(field)
        }
    }

    private class JvmAesGcm : ValueCipher {
        private val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")

        override fun encrypt(clear: ByteArray): ByteArray {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + cipher.doFinal(clear)
        }

        override fun decrypt(sealed: ByteArray): ByteArray = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
            cipher.doFinal(sealed, 12, sealed.size - 12)
        } catch (e: Exception) {
            throw UndecryptableValueException(e)
        }
    }

    /** The legacy scheme without its RSA wrapping of the key. */
    private class JvmAesCbc : ValueDecrypter {
        private val key = SecretKeySpec(ByteArray(32) { 2 }, "AES")

        fun encryptToText(clear: String): String {
            val iv = ByteArray(16).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv)) }
            return Base64.toBase64String(iv + cipher.doFinal(clear.toByteArray()))
        }

        override fun decrypt(sealed: ByteArray): ByteArray = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, key, IvParameterSpec(sealed, 0, 16)) }
            cipher.doFinal(sealed, 16, sealed.size - 16)
        } catch (e: Exception) {
            throw UndecryptableValueException(e)
        }
    }

    private class GarblingCipher : ValueCipher {
        override fun encrypt(clear: ByteArray) = clear
        override fun decrypt(sealed: ByteArray) = sealed + 0
    }

    private companion object {
        const val FIELD = "pin_code"
    }
}
