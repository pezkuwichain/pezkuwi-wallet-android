package io.novafoundation.nova.runtime.names

import java.math.BigInteger

/**
 * `pezpallet_navnisan::NameRecord`, decoded from the raw value of `Navnisan::Names[node]`.
 *
 * Decoded by hand rather than through runtime metadata: the resolver reads it through the light
 * client, before and independently of the chain's runtime being loaded.
 */
class NameRecord(
    val label: String,
    val parent: ByteArray,
    val parentReg: Long,
    val reg: Long,
    val owner: Owner,
    val kind: Kind,
    val lockedTransfer: Boolean,
    /** Milliseconds, chain time. Null for names that never expire. */
    val expiry: Long?,
    val account: ByteArray?,
    val content: Content?,
) {

    sealed interface Owner {
        class Account(val accountId: ByteArray) : Owner
        class Office(val tiki: Int) : Owner
        object Governance : Owner
    }

    enum class Kind { LEASED, GRANTED, SUB }

    sealed interface Content {
        /** An EIP-1577 contenthash: `0xe3` then a CIDv1. */
        class Ipfs(val contenthash: ByteArray) : Content

        class Feed(val publisher: ByteArray, val anchorer: ByteArray, val anchor: ByteArray, val seq: BigInteger) : Content
    }

    /** `NameRecord::is_official`: granted, or descended from a granted name. */
    val isOfficial: Boolean get() = lockedTransfer || kind == Kind.GRANTED

    /** `NameRecord::is_institutional`: changes to it wait out the institution delay. */
    val isInstitutional: Boolean get() = lockedTransfer || owner !is Owner.Account

    companion object {

        private const val ACCOUNT_ID = 32

        fun decode(bytes: ByteArray): NameRecord {
            val reader = ScaleReader(bytes)
            val record = with(reader) {
                NameRecord(
                    label = String(byteVec(), Charsets.US_ASCII),
                    parent = bytes(32),
                    parentReg = u64().toLong(),
                    reg = u64().toLong(),
                    owner = when (val index = u8()) {
                        0 -> Owner.Account(bytes(ACCOUNT_ID))
                        1 -> Owner.Office(u8())
                        2 -> Owner.Governance
                        else -> error("Unknown owner variant $index")
                    },
                    kind = when (val index = u8()) {
                        0 -> Kind.LEASED
                        1 -> Kind.GRANTED
                        2 -> Kind.SUB
                        else -> error("Unknown kind variant $index")
                    },
                    lockedTransfer = bool(),
                    expiry = option { u64().toLong() },
                    account = option { bytes(ACCOUNT_ID) },
                    content = option {
                        when (val index = u8()) {
                            0 -> Content.Ipfs(byteVec())
                            1 -> Content.Feed(publisher = bytes(32), anchorer = bytes(ACCOUNT_ID), anchor = byteVec(), seq = u64())
                            else -> error("Unknown content variant $index")
                        }
                    },
                ).also {
                    // deposit: Option<(AccountId, Balance)>, read only so a trailing byte is noticed
                    option { bytes(ACCOUNT_ID + 16) }
                }
            }
            require(reader.isExhausted) { "Trailing bytes after a name record" }

            return record
        }
    }
}

/** The few SCALE shapes a name record uses. */
internal class ScaleReader(private val data: ByteArray) {

    private var position = 0

    val isExhausted: Boolean get() = position == data.size

    fun u8(): Int {
        require(position < data.size) { "Unexpected end of SCALE input" }
        return data[position++].toInt() and 0xff
    }

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && position + count <= data.size) { "Unexpected end of SCALE input" }
        return data.copyOfRange(position, position + count).also { position += count }
    }

    fun bool(): Boolean = when (val byte = u8()) {
        0 -> false
        1 -> true
        else -> error("Invalid bool byte $byte")
    }

    fun u64(): BigInteger = BigInteger(1, bytes(8).reversedArray())

    fun compact(): Int {
        val first = u8()
        return when (first and 0b11) {
            0 -> first ushr 2
            1 -> (first or (u8() shl 8)) ushr 2
            2 -> ((first or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)) ushr 2).also { require(it >= 0) }
            else -> error("Big compact lengths are not used by name records")
        }
    }

    fun byteVec(): ByteArray = bytes(compact())

    fun <T> option(read: ScaleReader.() -> T): T? = when (val tag = u8()) {
        0 -> null
        1 -> read()
        else -> error("Invalid option tag $tag")
    }
}
