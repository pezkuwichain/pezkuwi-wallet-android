package io.novafoundation.nova.runtime.multiNetwork.connection.light

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightSpecsTest {

    @Test
    fun `a relay chain specification is replaced only by a later checkpoint`() {
        assertTrue(LightSpecs.isUpdate(relay(checkpoint = 1_000), relay(checkpoint = 1_020_017)))
        assertFalse(LightSpecs.isUpdate(relay(checkpoint = 1_020_017), relay(checkpoint = 1_000)))
        assertFalse(LightSpecs.isUpdate(relay(checkpoint = 1_000), relay(checkpoint = 1_000)))
    }

    @Test
    fun `a checkpoint does not move to another chain`() {
        assertFalse(LightSpecs.isUpdate(relay(checkpoint = 1), relay(checkpoint = 2, stateRoot = "0x02")))
        assertFalse(LightSpecs.isUpdate(relay(checkpoint = 1), relay(checkpoint = 2, id = "pezkuwi")))
    }

    @Test
    fun `a chain without a checkpoint takes any change of the same chain`() {
        assertTrue(LightSpecs.isUpdate(people(bootNode = "a"), people(bootNode = "b")))
        assertFalse(LightSpecs.isUpdate(people(bootNode = "a"), people(bootNode = "a")))
    }

    @Test
    fun `a specification that dropped its checkpoint is not taken`() {
        assertFalse(LightSpecs.isUpdate(relay(checkpoint = 5), relay(checkpoint = null)))
    }

    @Test
    fun `an unreadable candidate is not taken`() {
        assertFalse(LightSpecs.isUpdate(relay(checkpoint = 5), "<html>"))
        assertFalse(LightSpecs.isUpdate(relay(checkpoint = 5), "{}"))
    }

    @Test
    fun `compact numbers of every width are read`() {
        for (number in listOf(0, 63, 64, 16_383, 16_384, 1_073_741_823, 1_073_741_824)) {
            assertTrue("$number", LightSpecs.isUpdate(relay(checkpoint = number.toLong()), relay(checkpoint = number.toLong() + 1)))
            assertFalse("$number", LightSpecs.isUpdate(relay(checkpoint = number.toLong() + 1), relay(checkpoint = number.toLong())))
        }
    }

    private fun relay(checkpoint: Long?, stateRoot: String = "0x01", id: String = "zagros"): String {
        val sync = checkpoint?.let { """, "lightSyncState": {"finalizedBlockHeader": "0x${"00".repeat(32)}${compact(it)}${"00".repeat(64)}"}""" } ?: ""
        return """{"id": "$id", "genesis": {"stateRootHash": "$stateRoot"}, "bootNodes": []$sync}"""
    }

    private fun people(bootNode: String) = """{"id": "people-zagros", "genesis": {"stateRootHash": "0x03"}, "bootNodes": ["$bootNode"]}"""

    /** SCALE compact encoding, little endian, as hex. */
    private fun compact(value: Long): String {
        val bytes = when {
            value < 1 shl 6 -> byteArrayOf((value shl 2).toByte())
            value < 1 shl 14 -> le((value shl 2) or 0b01, 2)
            value < 1 shl 30 -> le((value shl 2) or 0b10, 4)
            else -> {
                val body = le(value, 8).dropLastWhile { it == 0.toByte() }.toByteArray()
                byteArrayOf((((body.size - 4) shl 2) or 0b11).toByte()) + body
            }
        }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun le(value: Long, size: Int) = ByteArray(size) { (value shr (8 * it)).toByte() }
}
