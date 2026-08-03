package io.novafoundation.nova.feature_account_migration

import io.novafoundation.nova.feature_account_migration.utils.common.KeyExchangeUtils
import io.novafoundation.nova.feature_account_migration.utils.common.bytes
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.experimental.xor


class ExchangeSecretsTest {

    private val keyExchangeUtils = KeyExchangeUtils()

    @Test
    fun checkExchangingSecretsFlow() {
        val encodingData = "PolkadotApp"

        val peerA = keyExchangeUtils.generateEphemeralKeyPair()
        val peerB = keyExchangeUtils.generateEphemeralKeyPair()

        val encoded = keyExchangeUtils.encrypt( encodingData.toByteArray(), peerA, peerB.public)

        val decoded = keyExchangeUtils.decrypt(encoded, peerB, peerA.public)

        val decodedString = decoded.decodeToString()

        assertEquals(encodingData, decodedString)
    }

    @Test(expected = AEADBadTagException::class)
    fun checkImposterSabotageFailed() {
        val encodingData = "PolkadotApp"

        val peerA = keyExchangeUtils.generateEphemeralKeyPair()
        val peerB = keyExchangeUtils.generateEphemeralKeyPair()
        val imposter = keyExchangeUtils.generateEphemeralKeyPair()

        val encoded = keyExchangeUtils.encrypt(encodingData.toByteArray(), peerA, peerB.public)

        val imposterDecoded = keyExchangeUtils.decrypt(encoded, imposter, peerA.public)

        imposterDecoded.decodeToString()
    }

    @Test
    fun checkSymmetricSecretsBothWays() {
        val msgFromA = "Message from A".toByteArray()
        val msgFromB = "Reply from B".toByteArray()

        val peerA = keyExchangeUtils.generateEphemeralKeyPair()
        val peerB = keyExchangeUtils.generateEphemeralKeyPair()

        val aToB = keyExchangeUtils.encrypt(msgFromA, peerA, peerB.public)
        val decodedByB = keyExchangeUtils.decrypt(aToB, peerB, peerA.public)
        assertEquals("Message from A", decodedByB.decodeToString())

        val bToA = keyExchangeUtils.encrypt(msgFromB, peerB, peerA.public)
        val decodedByA = keyExchangeUtils.decrypt(bToA, peerA, peerB.public)
        assertEquals("Reply from B", decodedByA.decodeToString())
    }

    @Test(expected = AEADBadTagException::class)
    fun checkTamperedDataFails() {
        val peerA = keyExchangeUtils.generateEphemeralKeyPair()
        val peerB = keyExchangeUtils.generateEphemeralKeyPair()

        val encoded = keyExchangeUtils.encrypt("Hello".toByteArray(), peerA, peerB.public)

        // Flip a bit in the byte being written, not in a different one: reading last()
        // while writing lastIndex - 1 leaves the array untouched whenever those two
        // bytes already differ by exactly 0x01, which is 1 run in 256. Decryption then
        // succeeds, no AEADBadTagException is thrown, and a tamper-detection test
        // reports a failure that has nothing to do with tamper detection.
        encoded[encoded.lastIndex - 1] = (encoded[encoded.lastIndex - 1] xor 0x01)

        keyExchangeUtils.decrypt(encoded, peerB, peerA.public)
    }

    @Test
    fun checkPublicKeyMapping() {
        val keyPair = keyExchangeUtils.generateEphemeralKeyPair()

        val bytes = keyPair.public.bytes()
        val mappedPublicKey = keyExchangeUtils.mapPublicKeyFromBytes(bytes)
        assertTrue(mappedPublicKey.bytes().contentEquals(keyPair.public.bytes()))
    }
}
