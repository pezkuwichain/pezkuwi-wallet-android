package io.novafoundation.nova.runtime.multiNetwork.connection.light

import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId

/**
 * Who is using which light client chain. A chain is wanted by more than one reader at a time -- a
 * connection that fell back to it, the `.kurd` resolver -- so one reader letting go must not stop
 * it under another. Holders are compared by identity, so asking twice and releasing once balances.
 */
internal class LightClientLeases(private val relayOf: (ChainId) -> ChainId?) {

    private val holders = mutableMapOf<ChainId, MutableSet<Any>>()

    fun acquire(chainId: ChainId, holder: Any) {
        holders.getOrPut(chainId) { mutableSetOf() } += holder
    }

    fun release(chainId: ChainId, holder: Any) {
        val chainHolders = holders[chainId] ?: return
        chainHolders -= holder
        if (chainHolders.isEmpty()) holders -= chainId
    }

    fun isHeld(chainId: ChainId): Boolean = holders[chainId].orEmpty().isNotEmpty()

    /** One of [registered] that nothing holds and that is not the relay of a registered chain. */
    fun nextUnused(registered: Set<ChainId>): ChainId? = registered.firstOrNull { key ->
        !isHeld(key) && registered.none { relayOf(it) == key }
    }
}
