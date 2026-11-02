package io.novafoundation.nova.feature_xcm_api.multiLocation

import io.novafoundation.nova.common.utils.HexString
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction.GlobalConsensus
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.NetworkId
import io.novafoundation.nova.feature_xcm_api.versions.XcmVersion
import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.Struct
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiLocationEncodingTest {

    // PezkuwiChain runtimes declare `ThisNetwork = ByGenesis(..)`, and XCM v5 has no named variant for them
    @Test
    fun `zagros is encoded by its genesis`() {
        assertEncodedByGenesis(ZAGROS)
    }

    @Test
    fun `pezkuwi is encoded by its genesis`() {
        assertEncodedByGenesis(PEZKUWI)
    }

    private fun assertEncodedByGenesis(genesis: HexString) {
        val location = RelativeMultiLocation(parents = 1, interior = Junctions(GlobalConsensus(NetworkId.Substrate(genesis))))

        val encoded = location.toEncodableInstanceExt(XcmVersion.V5) as Struct.Instance
        val junction = ((encoded["interior"] as DictEnum.Entry<*>).value as List<*>).single() as DictEnum.Entry<*>
        val network = junction.value as DictEnum.Entry<*>

        assertEquals("GlobalConsensus", junction.name)
        assertEquals("ByGenesis", network.name)
        assertEquals(genesis, (network.value as ByteArray).toHexString(withPrefix = false))
    }

    private companion object {
        const val ZAGROS = "1b0b4727bbb5e44ed587d1056b4ad537ffb330034509a58866a088d77e792cd5"
        const val PEZKUWI = "1aa94987791a5544e9667ec249d2cef1b8fdd6083c85b93fc37892d54a1156ca"
    }
}
