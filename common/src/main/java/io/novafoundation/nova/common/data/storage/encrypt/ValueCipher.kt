package io.novafoundation.nova.common.data.storage.encrypt

/** Decrypts a stored value, throwing [UndecryptableValueException] when it cannot. */
interface ValueDecrypter {

    fun decrypt(sealed: ByteArray): ByteArray
}

interface ValueCipher : ValueDecrypter {

    fun encrypt(clear: ByteArray): ByteArray
}

class UndecryptableValueException(cause: Throwable) : Exception("Stored value cannot be decrypted", cause)
