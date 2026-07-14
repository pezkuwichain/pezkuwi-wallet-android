package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class DerSignatureTest {

    private val curveOrder = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    private fun ByteArray.pad32() = ByteArray(32 - size) + this

    @Test
    fun `small r and high-bit s should each get a leading zero byte per DER minimal-integer rule`() {
        // r = 1 (0x01, high bit clear -> no padding needed), s = 128 (0x80, high bit set -> needs 0x00 prefix
        // so it isn't misread as a negative two's-complement integer). Manually verified expected DER bytes.
        val r = BigInteger.valueOf(1).toByteArray().pad32()
        val s = BigInteger.valueOf(128).toByteArray().pad32() // 128 < half-curve-order, so no low-S flip happens

        val der = DerSignature.encode(r, s)

        assertEquals("30070201010202" + "0080", der.toHexString(withPrefix = false))
    }

    @Test
    fun `high-S signature should be normalized to low-S per BIP62`() {
        val r = BigInteger.valueOf(42).toByteArray().pad32()
        val highS = curveOrder.subtract(BigInteger.ONE) // curveOrder - 1: definitely > halfCurveOrder
        val s = highS.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }.pad32()

        val der = DerSignature.encode(r, s)

        // Expect the DER-encoded s to equal curveOrder - highS == 1, not the original high-S value.
        val expectedNormalizedS = curveOrder.subtract(highS)
        assertEquals(BigInteger.ONE, expectedNormalizedS)

        // Extract the s component back out of the DER bytes to check it against the expected normalized value.
        val rLen = der[3].toInt()
        val sTagIndex = 4 + rLen
        val sLen = der[sTagIndex + 1].toInt()
        val sBytes = der.copyOfRange(sTagIndex + 2, sTagIndex + 2 + sLen)
        assertEquals(expectedNormalizedS, BigInteger(1, sBytes))
    }

    @Test
    fun `already-low-S signature should be left unchanged`() {
        val r = BigInteger.valueOf(7).toByteArray().pad32()
        val lowS = BigInteger.valueOf(12345)
        val s = lowS.toByteArray().pad32()

        val der = DerSignature.encode(r, s)

        val rLen = der[3].toInt()
        val sTagIndex = 4 + rLen
        val sLen = der[sTagIndex + 1].toInt()
        val sBytes = der.copyOfRange(sTagIndex + 2, sTagIndex + 2 + sLen)
        assertEquals(lowS, BigInteger(1, sBytes))
    }

    @Test
    fun `DER output should start with SEQUENCE tag and correct overall length`() {
        val r = "0011223344556677889900112233445566778899aabbccddeeff0011223344".fromHex()
        val s = "1122334455667788990011223344556677889900112233445566778899aabb".fromHex()

        val der = DerSignature.encode(r, s)

        assertEquals(0x30.toByte(), der[0])
        assertEquals(der.size - 2, der[1].toInt())
    }
}
