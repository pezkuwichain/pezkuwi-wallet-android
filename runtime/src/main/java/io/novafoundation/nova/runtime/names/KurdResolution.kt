package io.novafoundation.nova.runtime.names

sealed interface KurdResolution {

    val name: KurdName

    /**
     * [verified]: read through the light client, which checks every value against the chain's
     * finalized state. Otherwise two independent RPC nodes returned the same records, and the
     * name is shown with a "not verified" warning.
     */
    class Resolved(
        override val name: KurdName,
        val record: NameRecord,
        val status: NameStatus,
        val verified: Boolean,
        /** The finalized block the name was read at, when known. */
        val block: Long? = null,
    ) : KurdResolution {

        /** "Official" badge: only granted names and their descendants; a bought name never shows it. */
        val official: Boolean get() = record.isOfficial
    }

    /** Not registered, expired, or orphaned by its parent's re-registration. */
    class NotFound(override val name: KurdName, val verified: Boolean) : KurdResolution

    /**
     * Not verified, and a name that must never open unverified: official, institutional, reserved
     * or under a zone (decision D18).
     */
    class Refused(override val name: KurdName) : KurdResolution

    /** Neither the light client nor two agreeing RPC nodes could answer. */
    class Unavailable(override val name: KurdName) : KurdResolution
}

interface KurdNameResolver {

    suspend fun resolve(name: KurdName): KurdResolution
}
