package io.novafoundation.nova.lightclient

/**
 * Resolves the host names of light client peers, so they follow the app's DNS policy.
 *
 * Called from native threads, never the main thread. Throw (for example
 * [java.net.UnknownHostException]) when the name has no address.
 */
fun interface HostResolver {

    /** Addresses of [host] as IP literals, IPv4 or IPv6. */
    fun resolve(host: String): Array<String>
}
