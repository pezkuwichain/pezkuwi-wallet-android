package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.statemine

import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import io.novasama.substrate_sdk_android.runtime.metadata.module.MetadataFunction
import io.novasama.substrate_sdk_android.runtime.metadata.module.Module
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class AssetTransferPartiesTest {

    private val origin = ByteArray(32) { 1 }
    private val owner = ByteArray(32) { 2 }
    private val destination = ByteArray(32) { 3 }

    @Test
    fun `transfer goes from the signer to the target`() {
        val (sender, recipient) = call("transfer", "target" to destination).assetTransferParties("Assets", origin)!!

        assertArrayEquals(origin, sender)
        assertArrayEquals(destination, recipient)
    }

    @Test
    fun `transfer approved goes from the owner to the destination, not from the signing delegate`() {
        val (sender, recipient) = call("transfer_approved", "owner" to owner, "destination" to destination)
            .assetTransferParties("Assets", origin)!!

        assertArrayEquals(owner, sender)
        assertArrayEquals(destination, recipient)
    }

    @Test
    fun `other calls are not transfers`() {
        assertNull(call("approve_transfer", "delegate" to destination).assetTransferParties("Assets", origin))
        assertNull(call("transfer", "target" to destination, module = "ForeignAssets").assetTransferParties("Assets", origin))
    }

    private fun call(name: String, vararg arguments: Pair<String, Any?>, module: String = "Assets") = GenericCall.Instance(
        module = Module(module, null, null, null, emptyMap(), emptyMap(), BigInteger.ZERO),
        function = MetadataFunction(name, emptyList(), emptyList(), 0 to 0),
        arguments = mapOf("id" to BigInteger.ONE, "amount" to BigInteger.TEN, *arguments)
    )
}
