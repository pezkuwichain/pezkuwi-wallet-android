package io.novafoundation.nova.common.data.network.dns

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

class EncryptedFirstDnsTest {

    private val honest = InetAddress.getByName("203.0.113.7")
    private val poisoned = InetAddress.getByName("192.0.2.66")

    private var now = 0L
    private val calls = mutableListOf<String>()

    private fun resolver(name: String, answer: () -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            calls += "$name:$hostname"
            return answer()
        }
    }

    private val system = resolver("system") { listOf(poisoned) }

    private fun unreachable(): Nothing = throw UnknownHostException("h").apply { initCause(IOException("connect timed out")) }

    @Test
    fun `the encrypted answer wins over the system resolver`() {
        val dns = EncryptedFirstDns(listOf(resolver("doh1") { listOf(honest) }), system)

        assertEquals(listOf(honest), dns.lookup("raw.githubusercontent.com"))
        assertEquals(listOf("doh1:raw.githubusercontent.com"), calls)
    }

    @Test
    fun `IPv4 addresses come first`() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val dns = EncryptedFirstDns(listOf(resolver("doh1") { listOf(v6, honest) }), system)

        assertEquals(listOf(honest, v6), dns.lookup("a.example"))
    }

    @Test
    fun `an unreachable resolver hands over to the next`() {
        val dns = EncryptedFirstDns(listOf(resolver("doh1") { unreachable() }, resolver("doh2") { listOf(honest) }), system)

        assertEquals(listOf(honest), dns.lookup("a.example"))
    }

    @Test
    fun `when every encrypted resolver fails the system resolver answers`() {
        val dns = EncryptedFirstDns(listOf(resolver("doh1") { unreachable() }), system)

        assertEquals(listOf(poisoned), dns.lookup("a.example"))
    }

    @Test
    fun `an unreachable resolver is not waited on again inside its window`() {
        val dns = EncryptedFirstDns(listOf(resolver("doh1") { unreachable() }), system, resolverDownMillis = 1_000, clock = { now })
        dns.lookup("a.example")
        calls.clear()

        dns.lookup("b.example")
        assertEquals(listOf("system:b.example"), calls)

        now += 1_001
        calls.clear()
        dns.lookup("c.example")
        assertEquals(listOf("doh1:c.example", "system:c.example"), calls)
    }

    @Test
    fun `a name that does not exist does not take the resolver out`() {
        val dns = EncryptedFirstDns(listOf(resolver("doh1") { throw UnknownHostException("x: NXDOMAIN") }), system, clock = { now })
        dns.lookup("missing.example")
        calls.clear()

        dns.lookup("b.example")
        assertEquals(listOf("doh1:b.example", "system:b.example"), calls)
    }

    @Test
    fun `addresses and localhost never leave the device`() {
        val dns = EncryptedFirstDns(listOf(resolver("doh1") { listOf(honest) }), resolver("system") { emptyList() })

        listOf("10.0.2.2", "::1", "localhost").forEach { dns.lookup(it) }

        assertEquals(listOf("system:10.0.2.2", "system:::1", "system:localhost"), calls)
    }
}
