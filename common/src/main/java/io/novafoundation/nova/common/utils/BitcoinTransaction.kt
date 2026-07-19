package io.novafoundation.nova.common.utils

import java.io.ByteArrayOutputStream

/** SHA256(SHA256(x)) - Bitcoin's standard "double SHA256", used for both txids and the BIP143 sighash. */
fun ByteArray.sha256d(): ByteArray = sha256().sha256()

/** Bitcoin's variable-length integer ("CompactSize") encoding, used throughout raw transaction serialization. */
fun Long.toBitcoinVarInt(): ByteArray {
    require(this >= 0) { "VarInt cannot encode a negative value: $this" }
    val out = ByteArrayOutputStream()
    when {
        this < 0xfd -> out.write(toInt())
        this <= 0xffff -> {
            out.write(0xfd)
            out.write(toInt() and 0xff)
            out.write((toInt() ushr 8) and 0xff)
        }
        this <= 0xffffffffL -> {
            out.write(0xfe)
            for (i in 0..3) out.write(((this ushr (8 * i)) and 0xff).toInt())
        }
        else -> {
            out.write(0xff)
            for (i in 0..7) out.write(((this ushr (8 * i)) and 0xff).toInt())
        }
    }
    return out.toByteArray()
}

private fun Int.toLeBytes(byteCount: Int): ByteArray = ByteArray(byteCount) { i -> ((this ushr (8 * i)) and 0xff).toByte() }

private fun Long.toLeBytes(byteCount: Int): ByteArray = ByteArray(byteCount) { i -> ((this ushr (8 * i)) and 0xff).toByte() }

/**
 * A single UTXO being spent, in the form needed to build and sign a transaction.
 *
 * @param txid the previous transaction's id in standard (RPC/explorer-display) byte order - this class reverses
 * it internally to the on-wire/internal order raw transactions actually use (see [reversedTxid]).
 */
data class BitcoinInput(
    val txid: ByteArray,
    val vout: Int,
    val valueSat: Long,
    val sequence: Long = 0xfffffffdL, // RBF-signaling (BIP125), matching the exchange's proven, already-live choice
) {
    init {
        require(txid.size == 32) { "txid must be 32 bytes, got ${txid.size}" }
    }

    fun reversedTxid(): ByteArray = txid.reversedArray()
}

data class BitcoinOutput(
    val valueSat: Long,
    val scriptPubKey: ByteArray,
)

/**
 * Builds and signs native SegWit (P2WPKH-only) Bitcoin transactions using BIP143 sighashes - hand-implemented
 * since no Bitcoin transaction library (bitcoinj/PSBT/etc.) is on this project's classpath (same rationale as
 * [Bech32]/[DerSignature]). Verified byte-for-byte against BIP143's official "Native P2WPKH" worked example,
 * including the fully serialized signed transaction - see [BitcoinTransactionTest].
 */
object BitcoinTransaction {

    private const val SIGHASH_ALL = 1

    /** P2PKH-shaped "scriptCode" BIP143 requires for a P2WPKH input - see BIP143's "Specification" section. */
    private fun p2wpkhScriptCode(accountId: ByteArray): ByteArray {
        require(accountId.size == 20)
        val script = byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) + accountId + byteArrayOf(0x88.toByte(), 0xac.toByte())
        return 25L.toBitcoinVarInt() + script
    }

    private fun serializeOutpoint(input: BitcoinInput): ByteArray = input.reversedTxid() + input.vout.toLeBytes(4)

    private fun hashPrevouts(inputs: List<BitcoinInput>): ByteArray =
        inputs.fold(ByteArray(0)) { acc, input -> acc + serializeOutpoint(input) }.sha256d()

    private fun hashSequence(inputs: List<BitcoinInput>): ByteArray =
        inputs.fold(ByteArray(0)) { acc, input -> acc + input.sequence.toLeBytes(4) }.sha256d()

    private fun serializeOutput(output: BitcoinOutput): ByteArray =
        output.valueSat.toLeBytes(8) + output.scriptPubKey.size.toLong().toBitcoinVarInt() + output.scriptPubKey

    private fun hashOutputs(outputs: List<BitcoinOutput>): ByteArray =
        outputs.fold(ByteArray(0)) { acc, output -> acc + serializeOutput(output) }.sha256d()

    /**
     * BIP143 sighash preimage + double-SHA256 for signing [inputIndex], which must be a P2WPKH input whose
     * pubkey hashes to [signingAccountId]. Always uses SIGHASH_ALL, no ANYONECANPAY/NONE/SINGLE - this app never
     * constructs those.
     */
    fun bip143Sighash(
        version: Int,
        inputs: List<BitcoinInput>,
        outputs: List<BitcoinOutput>,
        inputIndex: Int,
        signingAccountId: ByteArray,
        locktime: Int,
    ): ByteArray {
        val input = inputs[inputIndex]

        val preimage = version.toLeBytes(4) +
            hashPrevouts(inputs) +
            hashSequence(inputs) +
            serializeOutpoint(input) +
            p2wpkhScriptCode(signingAccountId) +
            input.valueSat.toLeBytes(8) +
            input.sequence.toLeBytes(4) +
            hashOutputs(outputs) +
            locktime.toLeBytes(4) +
            SIGHASH_ALL.toLeBytes(4)

        return preimage.sha256d()
    }

    /**
     * @param witnesses one (derSignatureWithoutSighashByte, compressedPublicKey) pair per input, in input order -
     * every input in this app's transactions is a P2WPKH input from this wallet's own single address, so every
     * witness has exactly 2 items (signature, pubkey), never a bare key-path/script-path Taproot witness or a
     * multisig-style stack.
     */
    fun serializeSigned(
        version: Int,
        inputs: List<BitcoinInput>,
        outputs: List<BitcoinOutput>,
        witnesses: List<Pair<ByteArray, ByteArray>>,
        locktime: Int,
    ): ByteArray {
        require(witnesses.size == inputs.size) { "Need exactly one witness per input" }

        val out = ByteArrayOutputStream()
        out.write(version.toLeBytes(4))
        out.write(0x00) // segwit marker
        out.write(0x01) // segwit flag
        out.write(inputs.size.toLong().toBitcoinVarInt())
        for (input in inputs) {
            out.write(input.reversedTxid())
            out.write(input.vout.toLeBytes(4))
            out.write(0L.toBitcoinVarInt()) // scriptSig: empty for a native SegWit input
            out.write(input.sequence.toLeBytes(4))
        }
        out.write(outputs.size.toLong().toBitcoinVarInt())
        for (output in outputs) {
            out.write(serializeOutput(output))
        }
        for ((derSignature, publicKey) in witnesses) {
            out.write(2L.toBitcoinVarInt()) // 2 witness items: signature, pubkey
            val sigWithHashType = derSignature + byteArrayOf(SIGHASH_ALL.toByte())
            out.write(sigWithHashType.size.toLong().toBitcoinVarInt())
            out.write(sigWithHashType)
            out.write(publicKey.size.toLong().toBitcoinVarInt())
            out.write(publicKey)
        }
        out.write(locktime.toLeBytes(4))

        return out.toByteArray()
    }

    /**
     * Estimated virtual size in vbytes for fee purposes - the same hardcoded heuristic already proven in
     * production by `pezkuwi-exchange/wallet-service` (`inputs*68 + outputs*31 + 11`), reused here rather than
     * computing an exact post-signing weight (which would require knowing final DER signature lengths ahead of
     * time - low-S-normalized DER signatures are 70-72 bytes almost always, making this heuristic accurate to
     * within a few vbytes in practice).
     */
    fun estimateVsize(inputCount: Int, outputCount: Int): Long = inputCount * 68L + outputCount * 31L + 11L
}
