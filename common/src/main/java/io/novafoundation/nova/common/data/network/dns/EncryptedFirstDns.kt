package io.novafoundation.nova.common.data.network.dns

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves names over DNS-over-HTTPS first, so a network that answers plain DNS with a wrong address does not
 * decide where the app connects; if every encrypted resolver fails, the system resolver answers, so no network
 * is worse off than before. A resolver that failed is skipped for [resolverDownMillis], so a network that blocks
 * DoH pays its timeout once, not on every lookup.
 */
class EncryptedFirstDns(
    private val resolvers: List<Dns>,
    private val fallback: Dns = Dns.SYSTEM,
    private val resolverDownMillis: Long = TimeUnit.MINUTES.toMillis(10),
    private val clock: () -> Long = System::currentTimeMillis,
) : Dns {

    companion object {

        /** Google, Cloudflare and Quad9, each reached by IP so resolving the resolver needs no plain DNS. */
        fun publicResolvers(bootstrapClient: OkHttpClient): List<Dns> = listOf(
            doh(bootstrapClient, "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"),
            doh(bootstrapClient, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"),
            doh(bootstrapClient, "https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112", "2620:fe::fe"),
        )

        private fun doh(client: OkHttpClient, url: String, vararg bootstrap: String): Dns = DnsOverHttps.Builder()
            .client(client)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(bootstrap.map { InetAddress.getByName(it) })
            .includeIPv6(true)
            .build()

        private val IP_LITERAL = Regex("^[0-9.]+$|:")
    }

    private val downUntil = ConcurrentHashMap<Int, Long>()

    override fun lookup(hostname: String): List<InetAddress> {
        // Addresses and local names never leave the device.
        if (IP_LITERAL.containsMatchIn(hostname) || hostname == "localhost") return fallback.lookup(hostname)

        resolvers.forEachIndexed { index, resolver ->
            if (isDown(index)) return@forEachIndexed

            try {
                val addresses = resolver.lookup(hostname)
                // DoH answers IPv6 whether or not this network has it; the system resolver would not. OkHttp tries
                // addresses in order, so IPv4 goes first and a broken IPv6 route cannot stall every connection.
                if (addresses.isNotEmpty()) return addresses.sortedBy { it is Inet6Address }
            } catch (e: UnknownHostException) {
                // DnsOverHttps reports an unreachable resolver with the I/O failure as the cause; NXDOMAIN and
                // SERVFAIL come without one and say nothing about the resolver.
                if (e.cause != null) markDown(index)
            }
        }

        return fallback.lookup(hostname)
    }

    private fun isDown(index: Int): Boolean {
        val until = downUntil[index] ?: return false
        if (clock() < until) return true
        downUntil.remove(index, until)
        return false
    }

    private fun markDown(index: Int) {
        downUntil[index] = clock() + resolverDownMillis
    }
}
