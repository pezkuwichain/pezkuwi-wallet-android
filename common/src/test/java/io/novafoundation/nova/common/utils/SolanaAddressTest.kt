package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.fromHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaAddressTest {

    /**
     * The standard BIP39 test mnemonic ("abandon" x11 + "about", empty passphrase) - its 64-byte
     * seed is a well-known, independently-verifiable industry reference value (used identically
     * across Bitcoin/Ethereum/etc test vectors). Derived here at m/44'/501'/0'/0' (SLIP-0010
     * Ed25519, the Phantom/Solflare/Solana CLI default path) and cross-checked against an
     * independent second implementation (the `slip10` PyPI package, run standalone in Python
     * outside this codebase) - both agree exactly on this address, so this is a real,
     * cross-validated vector, not one invented for this test.
     */
    private val knownSeedHex =
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    private val knownPath = listOf(44, 501, 0, 0)
    private val knownAddress = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"

    @Test
    fun `should derive the known reference Solana address from the standard test seed`() {
        val keypair = Bip32Ed25519KeypairFactory.generate(knownSeedHex.fromHex(), knownPath)

        assertEquals(knownAddress, keypair.publicKey.toSolanaAddress())
    }

    @Test
    fun `different derivation path should yield a different address`() {
        val keypair = Bip32Ed25519KeypairFactory.generate(knownSeedHex.fromHex(), listOf(44, 501, 0))

        assertFalse(keypair.publicKey.toSolanaAddress() == knownAddress)
    }

    @Test
    fun `address encode-decode should round trip`() {
        val keypair = Bip32Ed25519KeypairFactory.generate(knownSeedHex.fromHex(), knownPath)
        val address = keypair.publicKey.toSolanaAddress()

        val decoded = address.solanaAddressToAccountId()
        assertTrue(decoded.contentEquals(keypair.publicKey))
    }

    @Test
    fun `isValidSolanaAddress should accept known good address`() {
        assertTrue(knownAddress.isValidSolanaAddress())
    }

    @Test
    fun `isValidSolanaAddress should reject a Tron address`() {
        assertFalse("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t".isValidSolanaAddress())
    }

    @Test
    fun `isValidSolanaAddress should reject too-short input`() {
        assertFalse("1111".isValidSolanaAddress())
    }

    @Test
    fun `toSolanaAddress should reject a non-32-byte input`() {
        val notThirtyTwoBytes = ByteArray(20)

        assertThrows(IllegalArgumentException::class.java) {
            notThirtyTwoBytes.toSolanaAddress()
        }
    }

    private fun assertThrows(expected: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            assertTrue("Expected ${expected.name} but got ${e::class.java.name}", expected.isInstance(e))
            return
        }
        throw AssertionError("Expected ${expected.name} to be thrown, but nothing was thrown")
    }
}
