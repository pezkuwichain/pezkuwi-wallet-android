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
    "1aa94987791a5544e9667ec249d2cef1b8fdd6083c85b93fc37892d54a1156ca", // PEZKUWI
    "e7c15092dcbe3f320260ddbbc685bfceed9125a3b3d8436db2766201dec3b949", // PEZKUWI_ASSET_HUB
    "69a8d025ab7b63363935d7d9397e0f652826c94271c1bc55c4fdfe72cccf1cfa" // PEZKUWI_PEOPLE
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
