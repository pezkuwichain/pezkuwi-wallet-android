package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TronAddressTest {

    /**
     * TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t is the well-known Tron mainnet USDT (TRC-20) contract address
     * (the same one used in this app's Tron chain config). Its raw 21-byte Base58Check payload
     * (0x41 prefix ++ 20-byte account id) is publicly documented as
     * 41a614f803b6fd780986a42c78ec9c7f77e6ded13c - this is an independently-verifiable, real-world
     * test vector (not a value invented for this test).
     */
    private val knownTronAddress = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
    private val knownTronAddressHex = "41a614f803b6fd780986a42c78ec9c7f77e6ded13c"

    @Test
    fun `should decode known Tron address to expected raw bytes`() {
        val decodedPayload = Base58Check.decode(knownTronAddress)

        assertEquals(knownTronAddressHex, decodedPayload.toHexString(withPrefix = false))
    }

    @Test
    fun `should re-encode known raw bytes back to the exact known Tron address`() {
        val payload = knownTronAddressHex.fromHex()

        assertEquals(knownTronAddress, Base58Check.encode(payload))
    }

    @Test
    fun `accountId to address and back should round trip`() {
        val accountId = knownTronAddressHex.fromHex().copyOfRange(1, 21)

        val address = accountId.toTronAddress()
        assertEquals(knownTronAddress, address)

        val decodedBack = address.tronAddressToAccountId()
        assertTrue(decodedBack.contentEquals(accountId))
    }

    @Test
    fun `toTronHexAddress should produce the known hex form`() {
        val accountId = knownTronAddressHex.fromHex().copyOfRange(1, 21)

        assertEquals(knownTronAddressHex, accountId.toTronHexAddress())
    }

    @Test
    fun `tronAddressToHexAddress should produce the known hex form directly from a Base58 address`() {
        assertEquals(knownTronAddressHex, knownTronAddress.tronAddressToHexAddress())
    }

    @Test
    fun `isValidTronAddress should accept known good address`() {
        assertTrue(knownTronAddress.isValidTronAddress())
    }

    @Test
    fun `isValidTronAddress should reject corrupted address`() {
        val corrupted = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6u" // last char changed -> checksum mismatch
        assertFalse(corrupted.isValidTronAddress())
    }

    @Test
    fun `isValidTronAddress should reject a plain Ethereum-style address`() {
        assertFalse("0x9858EfFD232B4033E47d90003D41EC34EcaEda94".isValidTronAddress())
    }

    @Test
    fun `Base58 encode should preserve leading zero bytes as leading 1s`() {
        val input = byteArrayOf(0, 0, 1, 2, 3)

        val encoded = Base58.encode(input)
        assertTrue(encoded.startsWith("11"))

        val decoded = Base58.decode(encoded)
        assertTrue(decoded.contentEquals(input))
    }

    @Test
    fun `Base58 encode-decode should round trip for empty input`() {
        assertEquals("", Base58.encode(ByteArray(0)))
        assertTrue(Base58.decode("").isEmpty())
    }
}
