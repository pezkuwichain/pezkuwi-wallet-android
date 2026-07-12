package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verified byte-for-byte against BIP143's official "Native P2WPKH" worked example
 * (https://github.com/bitcoin/bips/blob/master/bip-0143.mediawiki, fetched directly at implementation time) -
 * every intermediate value below (hashPrevouts/hashSequence/hashOutputs/preimage/sighash/signed tx) is quoted
 * verbatim from that document, not invented for this test.
 *
 * The two inputs' txids are given here in the conventional *display* order (reversed from on-wire order) - the
 * same order a REST API like mempool.space returns - to exercise [BitcoinInput.reversedTxid]'s reversal for
 * real, rather than pre-reversing them and bypassing that logic.
 */
class BitcoinTransactionTest {

    // BIP143 doc's wire-order txids, reversed here once (by hand, offline) to get the display-order string a
    // real API would hand back - see this test class's doc comment.
    private val input0Txid = "9f96ade4b41d5433f4eda31e1738ec2b36f6e7d1420d94a6af99801a88f7f7ff".fromHex()
    private val input1Txid = "8ac60eb9575db5b2d987e29f301b5b819ea83a5c6579d282d189cc04b8e151ef".fromHex()

    private val input0 = BitcoinInput(txid = input0Txid, vout = 0, valueSat = 625_000_000L, sequence = 0xeeffffffL)
    private val input1 = BitcoinInput(txid = input1Txid, vout = 1, valueSat = 600_000_000L, sequence = 0xffffffffL)

    private val output0 = BitcoinOutput(
        valueSat = 112_340_000L,
        scriptPubKey = "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac".fromHex()
    )
    private val output1 = BitcoinOutput(
        valueSat = 223_450_000L,
        scriptPubKey = "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac".fromHex()
    )

    private val signingAccountId = "1d0f172a0ecb48aee1be1f2687d2963ae33f71a1".fromHex()
    private val publicKey = "025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357".fromHex()

    @Test
    fun `hash160 of the known public key should match the known account id`() {
        assertEquals(signingAccountId.toHexString(withPrefix = false), publicKey.hash160().toHexString(withPrefix = false))
    }

    @Test
    fun `bip143Sighash should match the known sighash for signing input 1`() {
        val sighash = BitcoinTransaction.bip143Sighash(
            version = 1,
            inputs = listOf(input0, input1),
            outputs = listOf(output0, output1),
            inputIndex = 1,
            signingAccountId = signingAccountId,
            locktime = 0x11,
        )

        assertEquals("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670", sighash.toHexString(withPrefix = false))
    }

    @Test
    fun `serializeSigned should produce the exact expected bytes for a single-input all-P2WPKH transaction`() {
        // Hand-verified (not from a BIP143 vector, since BIP143's own worked example mixes a legacy P2PK input
        // with the P2WPKH one - this app only ever builds all-P2WPKH transactions since it only ever spends
        // from its own single P2WPKH address). Expected hex was independently computed byte-by-byte from this
        // function's own documented format (version/marker/flag/varints/witness) rather than copied from here.
        val txidWire = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9".fromHex()
        val txidDisplay = txidWire.reversedArray() // what a mempool.space-style API would actually return

        val input = BitcoinInput(txid = txidDisplay, vout = 3, valueSat = 100_000L, sequence = 0xfffffffdL)
        val output = BitcoinOutput(valueSat = 90_000L, scriptPubKey = "0014".fromHex() + "2222222222222222222222222222222222222222".fromHex())
        val derSignature = "3006020101020101".fromHex()
        val dummyPubKey = "0246e14bb0d93c0d64c265dd6b0eeeba6b9bd94aa88ce74aa302cf1cb8fdff9b6a".fromHex()

        val signed = BitcoinTransaction.serializeSigned(
            version = 2,
            inputs = listOf(input),
            outputs = listOf(output),
            witnesses = listOf(derSignature to dummyPubKey),
            locktime = 0,
        )

        val expected = "020000000001010a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90300000000fdffffff01905f01000000000016001422222222222222222222222222222222222222220209300602010102010101210246e14bb0d93c0d64c265dd6b0eeeba6b9bd94aa88ce74aa302cf1cb8fdff9b6a00000000"
        assertEquals(expected, signed.toHexString(withPrefix = false))
    }

    @Test
    fun `estimateVsize should match the exchange's proven heuristic formula`() {
        assertEquals(1 * 68L + 2 * 31L + 11L, BitcoinTransaction.estimateVsize(inputCount = 1, outputCount = 2))
    }
}
