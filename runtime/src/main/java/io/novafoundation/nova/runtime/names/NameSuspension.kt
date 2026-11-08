package io.novafoundation.nova.runtime.names

import io.novasama.substrate_sdk_android.extensions.toHexString

/**
 * `pezpallet_navnisan::Suspension`, decoded from the raw value of `Navnisan::Suspensions[node]`:
 * the court, or the emergency council, has stopped a name until [until] (decision D23). While it
 * holds the name and every name under it resolve to nothing.
 */
data class NameSuspension(
    /** Milliseconds, chain time; the suspension ends by itself then. */
    val until: Long,
    /** The registration suspended; a name registered anew is not. */
    val reg: Long,
    /** Hash of the written reasons, hex with `0x`. */
    val reason: String,
    val by: By,
) {

    enum class By { COURT, EMERGENCY }

    /** `Pezpallet::suspension_of`: for this registration, and not yet run out. */
    fun holdsFor(record: NameRecord, now: Long): Boolean = reg == record.reg && now < until

    companion object {

        fun decode(bytes: ByteArray): NameSuspension {
            val reader = ScaleReader(bytes)
            val suspension = with(reader) {
                NameSuspension(
                    until = u64().toLong(),
                    reg = u64().toLong(),
                    reason = bytes(32).toHexString(withPrefix = true),
                    by = when (val index = u8()) {
                        0 -> By.COURT
                        1 -> By.EMERGENCY
                        else -> error("Unknown suspension variant $index")
                    },
                )
            }
            require(reader.isExhausted) { "Trailing bytes after a suspension" }

            return suspension
        }
    }
}
