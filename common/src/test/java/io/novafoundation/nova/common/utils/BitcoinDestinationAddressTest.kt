package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test vectors:
 * - [genesisP2pkhAddress] is Satoshi's genesis coinbase address - real, independently-verifiable, its hash160
 *   cross-checked via Python's hashlib/base58 at implementation time.
 * - [okxWithdrawalAddress] is a real address confirmed live: an OKX BTC withdrawal QR, whose bare (non-BIP21)
 *   content this app's original native-SegWit-only address validation rejected outright, producing a misleading
 *   "QR can't be decoded" error - the actual bug this file's code fixes.
 */
class BitcoinDestinationAddressTest {

    private val genesisP2pkhAddress = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val genesisP2pkhHash160 = "62e907b15cbf27d5425399ebf6f0fb50ebb88f18"

    private val piVanityP2shAddress = "3P14159f73E4gFr7JterCCQh9QjiTjiZrG"

    private val okxWithdrawalAddress = "3QBsCZAv5hsZSrDpTcQYEqd82TdA8Qr3g9"
    private val okxWithdrawalHash160 = "f6c78c3a049bfa34ed05b22ca9515414cdcb46d1"

    private val nativeSegwitAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    private val nativeSegwitAccountId = "751e76e8199196d454941c45d1b3a323f1433bd6"

    @Test
    fun `should decode a known P2PKH address to the correct hash160`() {
        val destination = genesisP2pkhAddress.decodeBitcoinDestination()

        assertTrue(destination is BitcoinDestination.P2pkh)
        assertEquals(genesisP2pkhHash160, (destination as BitcoinDestination.P2pkh).pubKeyHash.toHexString(withPrefix = false))
    }

    @Test
    fun `should decode a known P2SH address to a P2sh destination`() {
        val destination = piVanityP2shAddress.decodeBitcoinDestination()

        assertTrue(destination is BitcoinDestination.P2sh)
    }

    @Test
    fun `should decode the real OKX withdrawal address to the correct P2SH hash160`() {
        val destination = okxWithdrawalAddress.decodeBitcoinDestination()

        assertTrue(destination is BitcoinDestination.P2sh)
        assertEquals(okxWithdrawalHash160, (destination as BitcoinDestination.P2sh).scriptHash.toHexString(withPrefix = false))
    }

    @Test
    fun `should still decode a native segwit address as NativeSegwit`() {
        val destination = nativeSegwitAddress.decodeBitcoinDestination()

        assertTrue(destination is BitcoinDestination.NativeSegwit)
        assertEquals(nativeSegwitAccountId, (destination as BitcoinDestination.NativeSegwit).witnessProgram.toHexString(withPrefix = false))
    }

    @Test
    fun `isValidBitcoinDestinationAddress should accept P2PKH, P2SH and native segwit`() {
        assertTrue(genesisP2pkhAddress.isValidBitcoinDestinationAddress())
        assertTrue(piVanityP2shAddress.isValidBitcoinDestinationAddress())
        assertTrue(okxWithdrawalAddress.isValidBitcoinDestinationAddress())
        assertTrue(nativeSegwitAddress.isValidBitcoinDestinationAddress())
    }

    @Test
    fun `isValidBitcoinDestinationAddress should reject a corrupted checksum`() {
        assertFalse("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb".isValidBitcoinDestinationAddress())
    }

    @Test
    fun `isValidBitcoinDestinationAddress should reject a Tron address`() {
        assertFalse("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t".isValidBitcoinDestinationAddress())
    }

    @Test
    fun `P2SH destination should produce OP_HASH160 push-20 OP_EQUAL scriptPubKey`() {
        val destination = okxWithdrawalAddress.decodeBitcoinDestination()

        assertEquals("a914${okxWithdrawalHash160}87", destination.toScriptPubKey().toHexString(withPrefix = false))
    }

    @Test
    fun `P2PKH destination should produce OP_DUP OP_HASH160 push-20 OP_EQUALVERIFY OP_CHECKSIG scriptPubKey`() {
        val destination = genesisP2pkhAddress.decodeBitcoinDestination()

        assertEquals("76a914${genesisP2pkhHash160}88ac", destination.toScriptPubKey().toHexString(withPrefix = false))
    }

    @Test
    fun `NativeSegwit destination should produce OP_0 push-20 scriptPubKey`() {
        val destination = nativeSegwitAddress.decodeBitcoinDestination()

        assertEquals("0014$nativeSegwitAccountId", destination.toScriptPubKey().toHexString(withPrefix = false))
    }
}
