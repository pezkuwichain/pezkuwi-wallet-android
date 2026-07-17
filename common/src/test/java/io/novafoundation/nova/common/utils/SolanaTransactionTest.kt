package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test

class SolanaTransactionTest {

    /**
     * Independently built via the `solders` Python library (a real, widely-used Solana SDK, run standalone
     * outside this codebase): a System Program `Transfer` message from a fixed sender to a fixed recipient, a
     * fixed 123456789-lamport amount, and an all-zero 32-byte "recent blockhash" (chosen for a reproducible
     * fixed vector, not a real network value). This test asserts our hand-rolled message builder produces the
     * exact same bytes solders did for the identical inputs.
     */
    private val senderPublicKey = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5".fromHex()
    private val recipientPublicKey = "c8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b3".fromHex()
    private val recentBlockhash = ByteArray(32)
    private val lamports = 123456789L

    private val expectedMessageHex = "010001038a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5" +
        "c8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b39400000000000000000000000000000000" +
        "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "1020200010c0200000015cd5b0700000000"

    @Test
    fun `buildTransferMessage should match the independently-built solders reference vector`() {
        val message = SolanaTransaction.buildTransferMessage(senderPublicKey, recipientPublicKey, lamports, recentBlockhash)

        assertEquals(expectedMessageHex, message.toHexString(withPrefix = false))
    }

    @Test
    fun `serializeSigned should prefix a single-signature count byte and the signature before the message`() {
        val message = SolanaTransaction.buildTransferMessage(senderPublicKey, recipientPublicKey, lamports, recentBlockhash)
        val signature = ByteArray(64) { it.toByte() }

        val signedTx = SolanaTransaction.serializeSigned(message, signature)

        assertEquals(1, signedTx[0].toInt())
        assertEquals(signature.toList(), signedTx.copyOfRange(1, 65).toList())
        assertEquals(message.toList(), signedTx.copyOfRange(65, signedTx.size).toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `serializeSigned should reject a non-64-byte signature`() {
        val message = SolanaTransaction.buildTransferMessage(senderPublicKey, recipientPublicKey, lamports, recentBlockhash)

        SolanaTransaction.serializeSigned(message, ByteArray(63))
    }

    @Test
    fun `compact-u16 should single-byte-encode every value used by a simple transfer message`() {
        assertEquals(listOf<Byte>(3), SolanaCompactU16.encode(3).toList())
        assertEquals(listOf<Byte>(1), SolanaCompactU16.encode(1).toList())
        assertEquals(listOf<Byte>(2), SolanaCompactU16.encode(2).toList())
        assertEquals(listOf<Byte>(12), SolanaCompactU16.encode(12).toList())
    }

    @Test
    fun `compact-u16 should multi-byte-encode a value at and above 128`() {
        // 128 = 0b1_0000000 -> low 7 bits (0) with continuation bit set, then high bit (1)
        assertEquals(listOf(0x80.toByte(), 0x01.toByte()), SolanaCompactU16.encode(128).toList())
    }
}
