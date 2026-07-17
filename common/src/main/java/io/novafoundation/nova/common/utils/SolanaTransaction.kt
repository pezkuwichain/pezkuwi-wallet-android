package io.novafoundation.nova.common.utils

/**
 * Solana's "shortvec"/compact-u16 length-prefix encoding used throughout the wire format below - LEB128-style,
 * 7 payload bits per byte with a continuation bit, matching https://solana.com/docs/rpc/http's message layout.
 * Cross-validated byte-for-byte against the `solders` Python library's own serialization of a real System
 * Program transfer message before being ported here (values used in this file never exceed 127, so this always
 * emits a single byte in practice, but the general algorithm is implemented for correctness/robustness).
 */
object SolanaCompactU16 {

    fun encode(value: Int): ByteArray {
        require(value >= 0) { "compact-u16 cannot encode a negative value" }

        var remaining = value
        val bytes = mutableListOf<Byte>()

        while (true) {
            var elem = remaining and 0x7f
            remaining = remaining ushr 7

            if (remaining == 0) {
                bytes.add(elem.toByte())
                break
            } else {
                elem = elem or 0x80
                bytes.add(elem.toByte())
            }
        }

        return bytes.toByteArray()
    }
}

/** The System Program's id is the all-zero 32-byte pubkey (base58: 32 `1` characters) - not a derived/hashed value. */
private val SOLANA_SYSTEM_PROGRAM_ID: ByteArray = ByteArray(32)

/** `Transfer` is variant index 2 of the System Program's instruction enum. */
private const val SOLANA_SYSTEM_INSTRUCTION_TRANSFER = 2

object SolanaTransaction {

    /**
     * A legacy (unversioned) Message containing a single System Program `Transfer` instruction - the simplest,
     * maximally-compatible shape for a native SOL transfer (no address-lookup-tables/versioned-transaction
     * features needed). Layout: MessageHeader(3 bytes) + compact-u16 account count + accounts(32B each) +
     * recentBlockhash(32B) + compact-u16 instruction count + CompiledInstruction. Verified byte-for-byte
     * against `solders`' own serialization of an identical transfer message - see the class doc.
     *
     * [recentBlockhash] must be 32 raw bytes (Base58-decode the RPC's `blockhash` string before calling this).
     */
    fun buildTransferMessage(
        senderPublicKey: ByteArray,
        recipientPublicKey: ByteArray,
        lamports: Long,
        recentBlockhash: ByteArray,
    ): ByteArray {
        require(senderPublicKey.size == 32) { "senderPublicKey must be 32 bytes, got ${senderPublicKey.size}" }
        require(recipientPublicKey.size == 32) { "recipientPublicKey must be 32 bytes, got ${recipientPublicKey.size}" }
        require(recentBlockhash.size == 32) { "recentBlockhash must be 32 bytes, got ${recentBlockhash.size}" }
        require(lamports >= 0) { "lamports cannot be negative" }

        val accountKeys = listOf(senderPublicKey, recipientPublicKey, SOLANA_SYSTEM_PROGRAM_ID)

        val instructionData = ByteArray(4 + 8).also {
            writeUInt32LE(it, 0, SOLANA_SYSTEM_INSTRUCTION_TRANSFER)
            writeUInt64LE(it, 4, lamports)
        }

        val out = mutableListOf<Byte>()

        // MessageHeader: 1 required signature (sender), 0 readonly-signed, 1 readonly-unsigned (System Program)
        out.add(1)
        out.add(0)
        out.add(1)

        out.addAll(SolanaCompactU16.encode(accountKeys.size).asList())
        accountKeys.forEach { out.addAll(it.asList()) }

        out.addAll(recentBlockhash.asList())

        // Single instruction: System Program Transfer
        out.addAll(SolanaCompactU16.encode(1).asList())
        out.add(2) // program_id_index -> accountKeys[2] (System Program)
        out.addAll(SolanaCompactU16.encode(2).asList())
        out.add(0) // sender account index
        out.add(1) // recipient account index
        out.addAll(SolanaCompactU16.encode(instructionData.size).asList())
        out.addAll(instructionData.asList())

        return out.toByteArray()
    }

    /** A single-signer signed transaction: compact-u16(1) + the 64-byte Ed25519 signature + the message bytes. */
    fun serializeSigned(message: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes, got ${signature.size}" }

        return SolanaCompactU16.encode(1) + signature + message
    }

    private fun writeUInt32LE(buffer: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            buffer[offset + i] = (value ushr (8 * i)).toByte()
        }
    }

    private fun writeUInt64LE(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buffer[offset + i] = (value ushr (8 * i)).toByte()
        }
    }
}
