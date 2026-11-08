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

    @Test
    fun `a suspension of the name or of the name above it stops it`() = runBlocking {
        val govReg = NameRecord.decode(NameFixtures.GOV).reg
        val serokReg = NameRecord.decode(NameFixtures.SEROK_GOV).reg
        val live = { FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV) }

        assertEquals(null, (NameLookup.read(live(), serok) as NameLookup.Found).suspension)

        val own = NameLookup.read(live().suspend(serok, serokReg, NameSuspension.By.EMERGENCY), serok) as NameLookup.Found
        assertEquals(NameSuspension.By.EMERGENCY, own.suspension?.by)

        val above = NameLookup.read(live().suspend(gov, govReg, NameSuspension.By.COURT), serok) as NameLookup.Found
        assertEquals(NameSuspension.By.COURT, above.suspension?.by)
        assertEquals(NameFixtures.NOW + 1, above.suspension?.until)
    }

    @Test
    fun `a suspension that ran out or names another registration stops nothing`() = runBlocking {
        val serokReg = NameRecord.decode(NameFixtures.SEROK_GOV).reg
        val live = { FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV) }

        val ranOut = live().suspend(serok, serokReg, NameSuspension.By.COURT, until = NameFixtures.NOW)
        assertEquals(null, (NameLookup.read(ranOut, serok) as NameLookup.Found).suspension)

        val earlier = live().suspend(serok, serokReg - 1, NameSuspension.By.COURT)
        assertEquals(null, (NameLookup.read(earlier, serok) as NameLookup.Found).suspension)
    }

    @Test
    fun `a suspension above a name that is not a subname does not reach it`() = runBlocking {
        // serok.gov.kurd as if Parliament had granted it outright rather than set it under gov.kurd
        val granted = NameFixtures.SEROK_GOV.copyOf().also { it[SEROK_KIND_OFFSET] = 1 }
        check(NameRecord.decode(granted).kind == NameRecord.Kind.GRANTED)
        val reader = FakeReader().put(gov, NameFixtures.GOV).put(serok, granted)
            .suspend(gov, NameRecord.decode(NameFixtures.GOV).reg, NameSuspension.By.COURT)

        assertEquals(null, (NameLookup.read(reader, serok) as NameLookup.Found).suspension)
    }

    @Test
    fun `sources that disagree on a suspension do not agree`() = runBlocking {
        val serokReg = NameRecord.decode(NameFixtures.SEROK_GOV).reg
        val live = { FakeReader().put(gov, NameFixtures.GOV).put(serok, NameFixtures.SEROK_GOV) }

        val plain = NameLookup.read(live(), serok)
        val stopped = NameLookup.read(live().suspend(serok, serokReg, NameSuspension.By.COURT), serok)
        assertFalse(plain.agreesWith(stopped))
        assertTrue(stopped.agreesWith(NameLookup.read(live().suspend(serok, serokReg, NameSuspension.By.COURT), serok)))
    }

    @Test
    fun `suspensions are read from the pallet's own storage item`() {
        // twox128("Navnisan") ++ twox128("Suspensions"), computed apart from this code
        val prefix = "8dada8db66ab8573e0db54b9f7d276265dd5f3679e83b14ce6d7b388fae08646"
        assertEquals(prefix + "00".repeat(32), NameLookup.suspensionsKey(ByteArray(32)).toHexString())
    }

    @Test
    fun `a suspension decodes field by field and refuses trailing bytes`() {
        val raw = suspensionBytes(until = 0x0102, reg = 7, by = NameSuspension.By.EMERGENCY)
        val suspension = NameSuspension.decode(raw)

        assertEquals(0x0102L, suspension.until)
        assertEquals(7L, suspension.reg)
        assertEquals("0x" + "ab".repeat(32), suspension.reason)
        assertEquals(NameSuspension.By.EMERGENCY, suspension.by)
        assertTrue(runCatching { NameSuspension.decode(raw + 0) }.isFailure)
        assertTrue(runCatching { NameSuspension.decode(raw.copyOf(raw.size - 1) + 2) }.isFailure)
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

        fun suspend(name: KurdName, reg: Long, by: NameSuspension.By, until: Long = NameFixtures.NOW + 1) = apply {
            values[NameLookup.suspensionsKey(name.node).toHexString()] = suspensionBytes(until, reg, by)
        }

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

    companion object {

        /** Label (1 + 5), parent, parent_reg, reg, owner `Office(3)`: the kind byte follows. */
        private const val SEROK_KIND_OFFSET = 6 + 32 + 8 + 8 + 2

        /** `Suspension { until, reg, reason, by }` as the pallet encodes it. */
        fun suspensionBytes(until: Long, reg: Long, by: NameSuspension.By): ByteArray =
            le64(until) + le64(reg) + ByteArray(32) { 0xab.toByte() } + byteArrayOf(by.ordinal.toByte())

        private fun le64(value: Long) = ByteArray(8) { (value ushr (8 * it)).toByte() }
    }
}
