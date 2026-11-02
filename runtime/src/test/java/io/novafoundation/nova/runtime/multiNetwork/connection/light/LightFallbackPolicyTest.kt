package io.novafoundation.nova.runtime.multiNetwork.connection.light

import io.novafoundation.nova.runtime.multiNetwork.connection.light.LightFallbackPolicy.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightFallbackPolicyTest {

    private val minute = 60_000L
    private val policy = LightFallbackPolicy(initialBackoffMillis = minute, maxBackoffMillis = 4 * minute)

    @Test
    fun `light client takes over only after every RPC node failed in a row`() {
        assertFalse(policy.onRpcFailure(rpcNodeCount = 3, nowMillis = 0))
        assertFalse(policy.onRpcFailure(rpcNodeCount = 3, nowMillis = 0))
        assertEquals(Source.RPC, policy.source)

        assertTrue(policy.onRpcFailure(rpcNodeCount = 3, nowMillis = 0))
        assertEquals(Source.LIGHT, policy.source)
    }

    @Test
    fun `a connected RPC node resets the count`() {
        policy.onRpcFailure(rpcNodeCount = 2, nowMillis = 0)
        policy.onRpcConnected()

        assertFalse(policy.onRpcFailure(rpcNodeCount = 2, nowMillis = 0))
        assertTrue(policy.onRpcFailure(rpcNodeCount = 2, nowMillis = 0))
    }

    @Test
    fun `a single RPC node hands over on its first failure`() {
        assertTrue(policy.onRpcFailure(rpcNodeCount = 1, nowMillis = 0))
    }

    @Test
    fun `RPC is retried after the backoff and one more failure returns to the light client`() {
        policy.onRpcFailure(rpcNodeCount = 3, nowMillis = 0)
        policy.onRpcFailure(rpcNodeCount = 3, nowMillis = 0)
        policy.onRpcFailure(rpcNodeCount = 3, nowMillis = 0)

        assertFalse(policy.onTick(rpcNodeCount = 3, nowMillis = minute - 1))
        assertTrue(policy.onTick(rpcNodeCount = 3, nowMillis = minute))
        assertEquals(Source.RPC, policy.source)

        assertTrue(policy.onRpcFailure(rpcNodeCount = 3, nowMillis = minute))
        assertEquals(Source.LIGHT, policy.source)
    }

    @Test
    fun `the backoff doubles while RPC keeps failing, up to the maximum`() {
        var now = 0L
        policy.onRpcFailure(rpcNodeCount = 1, nowMillis = now)

        for (expectedWait in listOf(minute, 2 * minute, 4 * minute, 4 * minute)) {
            assertFalse(policy.onTick(rpcNodeCount = 1, nowMillis = now + expectedWait - 1))
            now += expectedWait
            assertTrue(policy.onTick(rpcNodeCount = 1, nowMillis = now))
            assertTrue(policy.onRpcFailure(rpcNodeCount = 1, nowMillis = now))
        }
    }

    @Test
    fun `a connected RPC retry resets the backoff`() {
        policy.onRpcFailure(rpcNodeCount = 1, nowMillis = 0)
        policy.onTick(rpcNodeCount = 1, nowMillis = minute)
        policy.onRpcFailure(rpcNodeCount = 1, nowMillis = minute)
        policy.onTick(rpcNodeCount = 1, nowMillis = 3 * minute)
        policy.onRpcConnected()

        assertTrue(policy.onRpcFailure(rpcNodeCount = 1, nowMillis = 10 * minute))
        assertFalse(policy.onTick(rpcNodeCount = 1, nowMillis = 11 * minute - 1))
        assertTrue(policy.onTick(rpcNodeCount = 1, nowMillis = 11 * minute))
    }

    @Test
    fun `an unavailable light client leaves the connection on RPC`() {
        policy.onRpcFailure(rpcNodeCount = 2, nowMillis = 0)
        policy.onRpcFailure(rpcNodeCount = 2, nowMillis = 0)
        policy.onLightUnavailable(rpcNodeCount = 2)

        assertEquals(Source.RPC, policy.source)
        assertFalse(policy.onTick(rpcNodeCount = 2, nowMillis = 10 * minute))
    }

    @Test
    fun `no failure is counted while on the light client`() {
        policy.onRpcFailure(rpcNodeCount = 1, nowMillis = 0)
        assertFalse(policy.onRpcFailure(rpcNodeCount = 1, nowMillis = 0))
        assertEquals(Source.LIGHT, policy.source)
    }
}
