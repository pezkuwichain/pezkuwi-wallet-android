package io.novafoundation.nova.lightclient

import java.io.File

/**
 * Embedded PezkuwiChain light client (smoldot fork `pezkuwichain/smoldot`).
 *
 * Every answer is verified against finalized block headers, so it does not depend on trusting
 * an RPC node. The client is reached like any JSON-RPC node, over a WebSocket bound to
 * 127.0.0.1 whose path carries a random per-process token: `chainUrl(start(dir), key)`.
 */
object PezkuwiLight {

    init {
        System.loadLibrary("pezkuwi_light")
    }

    /**
     * Starts the light client once per process and returns its loopback base URL. Later calls
     * return the same URL. [databaseDir] keeps each chain's finalized state between starts;
     * [resolver] resolves the host names of peers.
     */
    fun start(databaseDir: File, resolver: HostResolver): String = start(databaseDir.absolutePath, resolver)

    /**
     * Registers [chainSpec] under [key] and starts syncing it. A teyrchain names the [key] its
     * relay chain was registered under, which must come first.
     *
     * @throws IllegalStateException if the client is not started, the key is taken, or smoldot
     * rejects the specification
     */
    external fun addChain(key: String, chainSpec: String, relayChainKey: String?)

    /**
     * Stops syncing the chain under [key]. Connections already open keep working until they close.
     * Its database is kept, so a later [addChain] resumes from it.
     *
     * @throws IllegalStateException if the key is not registered, or is the relay chain of a
     * registered teyrchain
     */
    external fun removeChain(key: String)

    /**
     * Restricts new peer connections to secure WebSockets (`wss`, normally on port 443), which
     * look like any HTTPS WebSocket on the wire. Open connections are kept.
     */
    external fun setSecureOnly(secureOnly: Boolean)

    fun chainUrl(baseUrl: String, key: String): String = "$baseUrl/$key"

    private external fun start(databaseDir: String, resolver: HostResolver): String
}
