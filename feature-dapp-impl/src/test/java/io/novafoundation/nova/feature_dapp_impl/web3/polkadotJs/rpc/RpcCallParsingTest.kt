package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RpcCallParsingTest {

    @Test
    fun `params reach the node as the page wrote them`() {
        val message = """{"msgType":"pub(rpc.send)","request":{"method":"chain_getBlockHash","params":[123, "0x01", null, 18446744073709551615]}}"""

        val (method, params) = rpcCall(message)!!

        assertEquals("chain_getBlockHash", method)
        assertEquals("""[123,"0x01",null,18446744073709551615]""", Gson().newBuilder().serializeNulls().create().toJson(params))
    }

    @Test
    fun `missing params are none and anything malformed is no call`() {
        assertEquals(emptyList<Any>(), rpcCall("""{"request":{"method":"system_health"}}""")!!.second)
        assertNull(rpcCall("""{"request":{"method":"system_health","params":{"a":1}}}"""))
        assertNull(rpcCall("""{"request":{"method":7}}"""))
        assertNull(rpcCall("""{"request":"system_health"}"""))
        assertNull(rpcCall("not json"))
    }
}
