package io.novafoundation.nova.runtime.names

import io.novasama.substrate_sdk_android.extensions.toHexString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NameLookupTest {

    private val gov = KurdName.parse("gov.kurd")!!
    private val serok = KurdName.parse("serok.gov.kurd")!!

    @Test
    fun `a ministry subname resolves with its zone under the live records`() = runBlocking {
        val reader = FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV)

        val found = NameLookup.read(reader, serok) as NameLookup.Found

        assertEquals("serok", found.record.label)
        assertEquals(NameStatus.ACTIVE, found.status)
        assertEquals(2, found.rawChain.size)
        assertFalse(found.locked)
        assertEquals(1_284_551L, found.block)
    }

    @Test
    fun `every read is made at the finalized block`() = runBlocking {
        val reader = FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV)

        NameLookup.read(reader, serok)

        assertEquals(setOf(FakeReader.HEAD), reader.blocksRead)
    }

    @Test
    fun `an unregistered name is not found and a missing ancestor lapses it`() = runBlocking {
        assertSame(NameLookup.NotFound, NameLookup.read(FakeReader(), serok))
        assertSame(NameLookup.Lapsed, NameLookup.read(FakeReader().put(serok, NameFixtures.SEROK_GOV), serok))
    }

    @Test
    fun `a subname set against an earlier registration of its parent lapses`() = runBlocking {
        val reRegisteredGov = NameFixtures.GOV.copyOf().also { it[1 + 3 + 32 + 8] = 2 } // reg 1 -> 2
        val reader = FakeReader().put(gov, reRegisteredGov).put(serok, NameFixtures.SEROK_GOV)

        assertSame(NameLookup.Lapsed, NameLookup.read(reader, serok))
    }

    @Test
    fun `a record stored under another name's key is refused`() = runBlocking {
        val dks = KurdName.parse("dks.kurd")!!
        val reader = FakeReader().put(dks, NameFixtures.GOV)

        assertTrue(runCatching { NameLookup.read(reader, dks) }.isFailure)
    }

    @Test
    fun `a reserved label is reported`() = runBlocking {
        val dks = KurdName.parse("dks.kurd")!!
        val reader = FakeReader().put(dks, NameFixtures.DKS).lock(dks)

        assertTrue((NameLookup.read(reader, dks) as NameLookup.Found).locked)
    }

    @Test
    fun `a lease is graced for thirty days after it ends, a grant is not`() {
        val end = 1_000L
        val leased = record(kind = NameRecord.Kind.LEASED, expiry = end)
        val granted = record(kind = NameRecord.Kind.GRANTED, expiry = end)

        assertEquals(NameStatus.ACTIVE, NameLookup.ownStatus(leased, end - 1))
        assertEquals(NameStatus.GRACE, NameLookup.ownStatus(leased, end))
        assertEquals(NameStatus.GRACE, NameLookup.ownStatus(leased, end + NameLookup.GRACE_PERIOD_MILLIS - 1))
        assertEquals(null, NameLookup.ownStatus(leased, end + NameLookup.GRACE_PERIOD_MILLIS))
        assertEquals(null, NameLookup.ownStatus(granted, end))
    }

    @Test
    fun `a parent in grace graces its subname, an expired parent lapses it`() {
        val sub = record(kind = NameRecord.Kind.SUB, expiry = null, parentReg = 7)
        val parent = { expiry: Long -> record(kind = NameRecord.Kind.LEASED, expiry = expiry, reg = 7) }

        assertEquals(NameStatus.GRACE, NameLookup.effectiveStatus(listOf(sub, parent(500)), now = 600))
        assertEquals(null, NameLookup.effectiveStatus(listOf(sub, parent(500)), now = 500 + NameLookup.GRACE_PERIOD_MILLIS))
        assertEquals(NameStatus.ACTIVE, NameLookup.effectiveStatus(listOf(sub, parent(700)), now = 600))
    }

    @Test
    fun `a chain that ends in a subname is not valid`() {
        val sub = record(kind = NameRecord.Kind.SUB, expiry = null)

        assertEquals(null, NameLookup.effectiveStatus(listOf(sub), now = 0))
    }

    @Test
    fun `sources agree only on identical records`() = runBlocking {
        val one = FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV)
        val other = FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV.copyOf())
        val differs = FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV.copyOf().also { it[it.size - 3] = 0x55 })

        val first = NameLookup.read(one, serok)
        assertTrue(first.agreesWith(NameLookup.read(other, serok)))
        assertFalse(first.agreesWith(NameLookup.read(differs, serok)))
        assertFalse(first.agreesWith(NameLookup.NotFound))
    }

    private fun record(kind: NameRecord.Kind, expiry: Long?, reg: Long = 1, parentReg: Long = 1) = NameRecord(
        label = "x", parent = ByteArray(32), parentReg = parentReg, reg = reg, owner = NameRecord.Owner.Governance, kind = kind,
        lockedTransfer = false, expiry = expiry, account = null, content = null,
    )

    private class FakeReader : NameStorageReader {

        private val values = mutableMapOf<String, ByteArray>()
        val blocksRead = mutableSetOf<String>()

        init {
            values[NameLookup.timestampNowKey.toHexString()] = ByteArray(8) { (NameFixtures.NOW ushr (8 * it)).toByte() }
        }

        fun put(name: KurdName, record: ByteArray) = apply { values[NameLookup.namesKey(name.node).toHexString()] = record }

        fun lock(name: KurdName) = apply { values[NameLookup.lockedKey(name.node).toHexString()] = byteArrayOf(0) }

        override suspend fun finalizedHead() = HEAD

        override suspend fun blockNumber(at: String): Long = 1_284_551

        override suspend fun storage(key: ByteArray, at: String): ByteArray? {
            blocksRead += at
            return values[key.toHexString()]
        }

        companion object {
            const val HEAD = "0xfinalized"
        }
    }
}
