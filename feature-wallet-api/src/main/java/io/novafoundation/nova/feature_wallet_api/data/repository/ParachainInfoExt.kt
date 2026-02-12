package io.novafoundation.nova.feature_wallet_api.data.repository

import io.novafoundation.nova.feature_xcm_api.chain.XcmChain
import io.novafoundation.nova.feature_xcm_api.multiLocation.AbsoluteMultiLocation
import io.novafoundation.nova.feature_xcm_api.multiLocation.ChainLocation
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction.ParachainId.Companion.JUNCTION_TYPE_PARACHAIN
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction.ParachainId.Companion.JUNCTION_TYPE_TEYRCHAIN
import io.novafoundation.nova.feature_xcm_api.multiLocation.chainLocation
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.repository.ParachainInfoRepository

// Pezkuwi chain IDs - these chains use "Teyrchain" instead of "Parachain" in XCM
private val PEZKUWI_CHAIN_IDS = setOf(
    "bb4a61ab0c4b8c12f5eab71d0c86c482e03a275ecdafee678dea712474d33d75", // PEZKUWI
    "00d0e1d0581c3cd5c5768652d52f4520184018b44f56a2ae1e0dc9d65c00c948", // PEZKUWI_ASSET_HUB
    "58269e9c184f721e0309332d90cafc410df1519a5dc27a5fd9b3bf5fd2d129f8" // PEZKUWI_PEOPLE
)

private fun junctionTypeNameForChain(chainId: ChainId): String {
    return if (chainId in PEZKUWI_CHAIN_IDS) JUNCTION_TYPE_TEYRCHAIN else JUNCTION_TYPE_PARACHAIN
}

suspend fun ParachainInfoRepository.getXcmChain(chain: Chain): XcmChain {
    return XcmChain(paraId(chain.id), chain)
}

suspend fun ParachainInfoRepository.getChainLocation(chainId: ChainId): ChainLocation {
    val junctionTypeName = junctionTypeNameForChain(chainId)
    val location = AbsoluteMultiLocation.chainLocation(paraId(chainId), junctionTypeName)
    return ChainLocation(chainId, location)
}
