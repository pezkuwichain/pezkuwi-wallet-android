package io.novafoundation.nova.runtime.multiNetwork.connection.light

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger

/** Which of two light client chain specifications to keep. */
object LightSpecs {

    /**
     * True when [candidate] is the same chain as [current] and should replace it: a relay chain only
     * for a later checkpoint, so an older file never rolls the checkpoint back; a chain without a
     * checkpoint whenever it differs (boot nodes change).
     */
    fun isUpdate(current: String, candidate: String): Boolean {
        val currentSpec = parse(current) ?: return false
        val candidateSpec = parse(candidate) ?: return false

        if (currentSpec.chainId() != candidateSpec.chainId() || currentSpec.stateRootHash() != candidateSpec.stateRootHash()) return false

        val currentCheckpoint = currentSpec.checkpointNumber()
        val candidateCheckpoint = candidateSpec.checkpointNumber()

        return when {
            currentCheckpoint == null && candidateCheckpoint == null -> currentSpec != candidateSpec
            currentCheckpoint == null -> true
            candidateCheckpoint == null -> false
            else -> candidateCheckpoint > currentCheckpoint
        }
    }

    private fun parse(spec: String): JsonObject? = runCatching { JsonParser.parseString(spec).asJsonObject }.getOrNull()

    private fun JsonObject.chainId(): String? = get("id")?.asString

    private fun JsonObject.stateRootHash(): String? = getAsJsonObject("genesis")?.get("stateRootHash")?.asString

    /** Block number of the checkpoint header: SCALE, a 32-byte parent hash then a compact number. */
    private fun JsonObject.checkpointNumber(): BigInteger? = runCatching {
        val header = getAsJsonObject("lightSyncState").get("finalizedBlockHeader").asString.removePrefix("0x")
        decodeCompact(hexToBytes(header.substring(64, minOf(header.length, 64 + 18))))
    }.getOrNull()

    private fun decodeCompact(bytes: ByteArray): BigInteger {
        val first = bytes[0].toInt() and 0xff
        val littleEndian = { from: Int, count: Int -> BigInteger(1, bytes.copyOfRange(from, from + count).reversedArray()) }

        return when (first and 0b11) {
            0b00 -> BigInteger.valueOf((first shr 2).toLong())
            0b01 -> littleEndian(0, 2).shiftRight(2)
            0b10 -> littleEndian(0, 4).shiftRight(2)
            else -> littleEndian(1, (first shr 2) + 4)
        }
    }

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
