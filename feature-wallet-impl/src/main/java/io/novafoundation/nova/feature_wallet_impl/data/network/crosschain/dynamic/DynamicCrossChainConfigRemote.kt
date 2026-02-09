package io.novafoundation.nova.feature_wallet_impl.data.network.crosschain.dynamic

import io.novafoundation.nova.feature_wallet_impl.data.network.crosschain.JunctionsRemote
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId

class DynamicCrossChainTransfersConfigRemote(
    val assetsLocation: Map<String, DynamicReserveLocationRemote>?,
    // (ChainId, AssetId) -> ReserveId
    val reserveIdOverrides: Map<String, Map<Int, String>>,
    val chains: List<DynamicCrossChainOriginChainRemote>?,
    val customTeleports: List<CustomTeleportEntryRemote>?,
)

class CustomTeleportEntryRemote(
    val originChain: String,
    val destChain: String,
    val originAsset: Int
)

class DynamicReserveLocationRemote(
    val chainId: ChainId,
    val multiLocation: JunctionsRemote
)

class DynamicCrossChainOriginChainRemote(
    val chainId: ChainId,
    val assets: List<DynamicCrossChainOriginAssetRemote>
)

class DynamicCrossChainOriginAssetRemote(
    val assetId: Int,
    val xcmTransfers: List<DynamicXcmTransferRemote>,
)

class DynamicXcmTransferRemote(
    // New format: nested destination object
    val destination: XcmTransferDestinationRemote?,
    // Legacy format: chainId and assetId at root level
    val chainId: ChainId?,
    val assetId: Int?,
    val type: String?,
    val hasDeliveryFee: Boolean?,
    val supportsXcmExecute: Boolean?,
) {
    /**
     * Get the destination chainId, supporting both new and legacy formats.
     */
    fun getDestinationChainId(): ChainId {
        return destination?.chainId ?: chainId
            ?: throw IllegalStateException("XCM transfer has no destination chainId")
    }

    /**
     * Get the destination assetId, supporting both new and legacy formats.
     */
    fun getDestinationAssetId(): Int {
        return destination?.assetId ?: assetId
            ?: throw IllegalStateException("XCM transfer has no destination assetId")
    }
}

class XcmTransferDestinationRemote(
    val chainId: ChainId,
    val assetId: Int,
)
