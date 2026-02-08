package io.novafoundation.nova.feature_xcm_api.chain

import io.novafoundation.nova.feature_xcm_api.multiLocation.AbsoluteMultiLocation
import io.novafoundation.nova.feature_xcm_api.multiLocation.ChainLocation
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction.ParachainId.Companion.JUNCTION_TYPE_PARACHAIN
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction.ParachainId.Companion.JUNCTION_TYPE_TEYRCHAIN
import io.novafoundation.nova.feature_xcm_api.multiLocation.chainLocation
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import java.math.BigInteger

// Pezkuwi chain IDs - these chains use "Teyrchain" instead of "Parachain" in XCM
private val PEZKUWI_CHAIN_IDS = setOf(
    "bb4a61ab0c4b8c12f5eab71d0c86c482e03a275ecdafee678dea712474d33d75", // PEZKUWI
    "00d0e1d0581c3cd5c5768652d52f4520184018b44f56a2ae1e0dc9d65c00c948", // PEZKUWI_ASSET_HUB
    "58269e9c184f721e0309332d90cafc410df1519a5dc27a5fd9b3bf5fd2d129f8" // PEZKUWI_PEOPLE
)

class XcmChain(
    val parachainId: BigInteger?,
    val chain: Chain
)

fun XcmChain.absoluteLocation(): AbsoluteMultiLocation {
    val junctionTypeName = if (chain.id in PEZKUWI_CHAIN_IDS) JUNCTION_TYPE_TEYRCHAIN else JUNCTION_TYPE_PARACHAIN
    return AbsoluteMultiLocation.chainLocation(parachainId, junctionTypeName)
}

fun XcmChain.isRelay(): Boolean {
    return parachainId == null
}

fun XcmChain.isSystemChain(): Boolean {
    return parachainId != null && parachainId.toInt() in 1000 until 2000
}

fun XcmChain.chainLocation(): ChainLocation {
    return ChainLocation(chain.id, absoluteLocation())
}
