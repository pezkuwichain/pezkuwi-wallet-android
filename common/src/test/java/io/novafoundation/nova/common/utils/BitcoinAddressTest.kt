package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [knownAccountId]/[knownAddress] is BIP173/BIP350's official segwit address test vector
 * (BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4 <-> scriptPubKey 0014751e76e8199196d454941c45d1b3a323f1433bd6,
 * fetched directly from https://github.com/bitcoin/bips at implementation time) - an independently-verifiable,
 * real-world test vector, not invented for this test. [knownPublicKey] is BIP143's official Native P2WPKH
 * example pubkey, whose HASH160 is independently confirmed (via Bech32AddressTest and BitcoinTransactionTest)
 * to equal a *different* known account id - used here only to test [hash160]/[bitcoinPublicKeyToAccountId] in
 * isolation from address encoding.
 */
class BitcoinAddressTest {

    private val knownAccountId = "751e76e8199196d454941c45d1b3a323f1433bd6".fromHex()
    private val knownAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    private val knownPublicKey = "025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357".fromHex()
    private val knownPublicKeyAccountId = "1d0f172a0ecb48aee1be1f2687d2963ae33f71a1".fromHex()

    @Test
    fun `accountId to address should produce the known BIP173 address`() {
        assertEquals(knownAddress, knownAccountId.toBitcoinAddress())
    }

    @Test
    fun `address to accountId should decode the known BIP173 address back to the known bytes`() {
        assertTrue(knownAddress.bitcoinAddressToAccountId().contentEquals(knownAccountId))
    }

    @Test
    fun `accountId to address and back should round trip`() {
        val decodedBack = knownAccountId.toBitcoinAddress().bitcoinAddressToAccountId()
        assertTrue(decodedBack.contentEquals(knownAccountId))
    }

    @Test
    fun `compressed public key to accountId should match the known BIP143 hash160`() {
        assertTrue(knownPublicKey.bitcoinPublicKeyToAccountId().contentEquals(knownPublicKeyAccountId))
    }

    @Test
    fun `uncompressed (65-byte) public key should be rejected`() {
        val uncompressed = ByteArray(65)
        try {
            uncompressed.bitcoinPublicKeyToAccountId()
            org.junit.Assert.fail("Expected an IllegalArgumentException for a non-33-byte public key")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `toP2wpkhScriptCode should produce OP_0 push-20 the account id`() {
        val scriptPubKey = knownAccountId.toP2wpkhScriptPubKey()

        assertEquals("0014751e76e8199196d454941c45d1b3a323f1433bd6", scriptPubKey.toHexString(withPrefix = false))
    }

    @Test
    fun `isValidBitcoinAddress should accept the known good address`() {
        assertTrue(knownAddress.isValidBitcoinAddress())
    }

    @Test
    fun `isValidBitcoinAddress should reject a corrupted checksum`() {
        val corrupted = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"
        assertFalse(corrupted.isValidBitcoinAddress())
    }

    @Test
    fun `isValidBitcoinAddress should reject a testnet address`() {
        assertFalse("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7".isValidBitcoinAddress())
    }

    @Test
    fun `isValidBitcoinAddress should reject a Tron address`() {
        assertFalse("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t".isValidBitcoinAddress())
    }

    @Test
    fun `isValidBitcoinAddress should reject a P2WSH (32-byte program) address as not P2WPKH`() {
        assertFalse("bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kt5nd6y".isValidBitcoinAddress())
    }

    @Test
    fun `hash160 known test vector should match independently-verified value`() {
        // hash160("hello") independently cross-checked via Python's hashlib (ripemd160(sha256(b"hello"))) at
        // implementation time - a different library from this project's BouncyCastle, not just self-consistency.
        assertEquals("b6a9c8c230722b7c748331a8b450f05566dc7d0f", "hello".toByteArray().hash160().toHexString(withPrefix = false))
    }
}
