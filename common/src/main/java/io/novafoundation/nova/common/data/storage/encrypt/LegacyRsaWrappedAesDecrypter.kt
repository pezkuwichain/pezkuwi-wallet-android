package io.novafoundation.nova.common.data.storage.encrypt

import android.content.Context
import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.util.encoders.Base64

/**
 * Reads values written before the keystore AES-GCM key: AES-256-CBC under a random key that is kept in
 * shared preferences wrapped by a keystore RSA key (PKCS#1). Read-only, and only so those values can be
 * re-encrypted; nothing is written in this scheme anymore and no new RSA key is created.
 */
class LegacyRsaWrappedAesDecrypter(private val context: Context) : ValueDecrypter {

    private val aesKey by lazy(::unwrapAesKey)

    override fun decrypt(sealed: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(sealed, 0, BLOCK_SIZE))

        cipher.doFinal(sealed, BLOCK_SIZE, sealed.size - BLOCK_SIZE)
    } catch (e: Exception) {
        throw UndecryptableValueException(e)
    }

    private fun unwrapAesKey(): ByteArray {
        val wrapped = context.getSharedPreferences(KEY_ALIAS, Context.MODE_PRIVATE).getString(SECRET_KEY, null)
            ?: error("No wrapped legacy key")
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey? ?: error("No legacy RSA key")

        // PKCS#1 v1.5 is what these keys were wrapped with; decrypting is the only use left
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)

        return cipher.doFinal(Base64.decode(wrapped))
    }

    private companion object {
        const val KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "key_alias"
        const val SECRET_KEY = "secret_key"
        const val BLOCK_SIZE = 16
    }
}
