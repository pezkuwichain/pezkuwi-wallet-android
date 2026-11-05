package io.novafoundation.nova.runtime.names

import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.hash.Hasher.xxHash128
import java.math.BigInteger

/** Raw storage of the chain that keeps `.kurd` names, all read at one block. */
interface NameStorageReader {

    suspend fun finalizedHead(): String

    /** The raw value at [key] in block [at], or null when nothing is stored there. */
    suspend fun storage(key: ByteArray, at: String): ByteArray?
}

enum class NameStatus {
    ACTIVE,

    /** The lease has ended; the site still resolves and only the last owner may renew. */
    GRACE,
}

/** What one source says about a name. */
sealed interface NameLookup {

    /** Two sources agree when they return the same records byte for byte and judge them the same. */
    fun agreesWith(other: NameLookup): Boolean = when (this) {
        is Found -> other is Found && rawChain == other.rawChain && status == other.status && locked == other.locked
        else -> this === other
    }

    object NotFound : NameLookup

    /** Registered once but no longer valid: expired, or an ancestor was registered anew. */
    object Lapsed : NameLookup

    data class Found(
        val record: NameRecord,
        val status: NameStatus,
        /** Every record from this name up to its top-level name, raw, for comparing sources. */
        val rawChain: List<String>,
        /** Some label of the name is in `Navnisan::Locked`: reserved, never for sale. */
        val locked: Boolean,
    ) : NameLookup

    companion object {

        private val NAMES_PREFIX = "Navnisan".toByteArray().xxHash128() + "Names".toByteArray().xxHash128()
        private val LOCKED_PREFIX = "Navnisan".toByteArray().xxHash128() + "Locked".toByteArray().xxHash128()
        private val TIMESTAMP_NOW = "Timestamp".toByteArray().xxHash128() + "Now".toByteArray().xxHash128()

        /** `NavnisanGracePeriod`: 30 days after a lease ends the name still resolves (decision D11). */
        const val GRACE_PERIOD_MILLIS = 30L * 24 * 60 * 60 * 1000

        fun namesKey(node: ByteArray) = NAMES_PREFIX + node

        fun lockedKey(node: ByteArray) = LOCKED_PREFIX + node

        val timestampNowKey: ByteArray get() = TIMESTAMP_NOW.copyOf()

        /**
         * Reads [name] the way `Pallet::effective_status` judges it, all at one finalized block: the
         * record, then each ancestor. Each record must sit under the parent its name implies and
         * carry the label the name has, so a source cannot answer for one name with another's record.
         */
        suspend fun read(reader: NameStorageReader, name: KurdName): NameLookup {
            val at = reader.finalizedHead()
            val now = reader.storage(TIMESTAMP_NOW, at)?.let(::decodeMoment) ?: error("Chain time is missing")

            val nodes = name.nodesUpward()
            val parents = nodes.drop(1) + listOf(KurdName.KURD_NODE)
            val records = mutableListOf<Pair<NameRecord, String>>()

            for (level in nodes.indices) {
                val raw = reader.storage(namesKey(nodes[level]), at) ?: return if (level == 0) NotFound else Lapsed
                val record = NameRecord.decode(raw)
                check(record.label == name.labels[level] && record.parent.contentEquals(parents[level])) {
                    "Record of ${name.labels[level]} does not belong to $name"
                }
                records += record to raw.toHexString()
            }

            val status = effectiveStatus(records.map { it.first }, now) ?: return Lapsed
            val locked = nodes.any { reader.storage(lockedKey(it), at) != null }

            return Found(records.first().first, status, records.map { it.second }, locked)
        }

        /**
         * [chain] is a name's record followed by every ancestor's. A subname is valid only while
         * each ancestor is, under the registration it was set against; a grace period anywhere
         * above makes the whole name graced.
         */
        fun effectiveStatus(chain: List<NameRecord>, now: Long): NameStatus? {
            var status = ownStatus(chain.first(), now) ?: return null
            for (index in 0 until chain.lastIndex) {
                val record = chain[index]
                if (record.kind != NameRecord.Kind.SUB) return status

                val parent = chain[index + 1]
                if (parent.reg != record.parentReg) return null
                if ((ownStatus(parent, now) ?: return null) == NameStatus.GRACE) status = NameStatus.GRACE
            }

            return status.takeIf { chain.last().kind != NameRecord.Kind.SUB }
        }

        fun ownStatus(record: NameRecord, now: Long): NameStatus? {
            val end = record.expiry ?: return NameStatus.ACTIVE
            return when {
                now < end -> NameStatus.ACTIVE
                record.kind == NameRecord.Kind.LEASED && now < end + GRACE_PERIOD_MILLIS -> NameStatus.GRACE
                else -> null
            }
        }

        private fun decodeMoment(raw: ByteArray): Long {
            require(raw.size == 8) { "Chain time is not a u64" }
            return BigInteger(1, raw.reversedArray()).toLong()
        }
    }
}
