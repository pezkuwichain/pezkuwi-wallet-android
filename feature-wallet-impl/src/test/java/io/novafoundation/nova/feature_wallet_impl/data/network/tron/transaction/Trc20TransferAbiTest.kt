package io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction

import io.novasama.substrate_sdk_android.extensions.fromHex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class Trc20TransferAbiTest {

    /**
     * Real request/response pair captured live against TronGrid's Shasta testnet
     * (`POST https://api.shasta.trongrid.io/wallet/triggerconstantcontract`) for a `transfer(address,uint256)`
     * call with recipient accountId `dfd8703a5c753e17ed52a96a29cea9d425538dfe` and amount `1000000` (sun) -
     * TronGrid accepted this exact `parameter` value and correctly resolved `function_selector` to the standard
     * `a9059cbb` selector in the resulting `raw_data.contract[0].parameter.value.data`.
     */
    @Test
    fun `encodeTransferParameters should match a live-verified TronGrid request`() {
        val recipient = "dfd8703a5c753e17ed52a96a29cea9d425538dfe".fromHex()
        val amountSun = BigInteger.valueOf(1_000_000)

        val expectedParameter = "000000000000000000000000dfd8703a5c753e17ed52a96a29cea9d425538dfe" +
            "00000000000000000000000000000000000000000000000000000000000f4240"

        assertEquals(expectedParameter, Trc20TransferAbi.encodeTransferParameters(recipient, amountSun))
    }

    @Test
    fun `encodeTransferParameters should reject a negative amount`() {
        val recipient = "dfd8703a5c753e17ed52a96a29cea9d425538dfe".fromHex()

        assertThrowsIllegalArgument {
            Trc20TransferAbi.encodeTransferParameters(recipient, BigInteger.valueOf(-1))
        }
    }

    @Test
    fun `encodeTransferParameters should reject a non-20-byte account id`() {
        assertThrowsIllegalArgument {
            Trc20TransferAbi.encodeTransferParameters(ByteArray(19), BigInteger.ONE)
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
