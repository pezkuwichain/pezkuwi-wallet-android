package io.novafoundation.nova.runtime.multiNetwork.connection.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightClientLeasesTest {

    private val leases = LightClientLeases(relayOf = { if (it == PEOPLE) RELAY else null })
    private val registered = setOf(RELAY, PEOPLE)

    @Test
    fun `a chain stays while any holder still uses it`() {
        val connection = Any()
        val resolver = Any()
        leases.acquire(PEOPLE, connection)
        leases.acquire(PEOPLE, resolver)

        leases.release(PEOPLE, connection)
        assertNull(leases.nextUnused(registered))

        leases.release(PEOPLE, resolver)
        assertEquals(PEOPLE, leases.nextUnused(registered))
    }

    @Test
    fun `asking twice and releasing once frees the chain`() {
        val connection = Any()
        leases.acquire(PEOPLE, connection)
        leases.acquire(PEOPLE, connection)

        leases.release(PEOPLE, connection)

        assertEquals(PEOPLE, leases.nextUnused(registered))
    }

    @Test
    fun `a relay goes only after its teyrchains`() {
        assertEquals(PEOPLE, leases.nextUnused(registered))
        assertEquals(RELAY, leases.nextUnused(setOf(RELAY)))
    }

    private companion object {
        const val RELAY = "relay"
        const val PEOPLE = "people"
    }
}
