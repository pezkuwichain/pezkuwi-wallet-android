package io.novafoundation.nova.feature_wallet_api.domain.model.xcm.legacy

import io.novafoundation.nova.common.data.network.runtime.binding.ParaId
import io.novafoundation.nova.common.data.network.runtime.binding.Weight
import io.novafoundation.nova.common.utils.graph.Edge
import io.novafoundation.nova.common.utils.graph.SimpleEdge
import io.novafoundation.nova.feature_wallet_api.domain.model.xcm.legacy.LegacyCrossChainTransfersConfiguration.AssetTransfers
import io.novafoundation.nova.feature_xcm_api.chain.XcmChain
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Interior
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction.ParachainId.Companion.JUNCTION_TYPE_PARACHAIN
import io.novafoundation.nova.feature_xcm_api.multiLocation.MultiLocation.Junction.ParachainId.Companion.JUNCTION_TYPE_TEYRCHAIN
import io.novafoundation.nova.feature_xcm_api.multiLocation.RelativeMultiLocation
import io.novafoundation.nova.feature_xcm_api.multiLocation.toInterior
import io.novafoundation.nova.runtime.ext.fullId
import io.novafoundation.nova.runtime.ext.isParachain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.multiNetwork.chain.model.FullChainAssetId
import java.math.BigInteger
import io.novafoundation.nova.feature_wallet_api.domain.model.xcm.dynamic.reserve.XcmTransferType as XcmReserveTransferType

// Pezkuwi chain IDs - these chains use "Teyrchain" instead of "Parachain" in XCM
private val PEZKUWI_CHAIN_IDS = setOf(
    "bb4a61ab0c4b8c12f5eab71d0c86c482e03a275ecdafee678dea712474d33d75", // PEZKUWI
    "00d0e1d0581c3cd5c5768652d52f4520184018b44f56a2ae1e0dc9d65c00c948", // PEZKUWI_ASSET_HUB
    "58269e9c184f721e0309332d90cafc410df1519a5dc27a5fd9b3bf5fd2d129f8" // PEZKUWI_PEOPLE
)

private fun junctionTypeNameForChain(chainId: ChainId): String {
    return if (chainId in PEZKUWI_CHAIN_IDS) JUNCTION_TYPE_TEYRCHAIN else JUNCTION_TYPE_PARACHAIN
}

fun LegacyCrossChainTransfersConfiguration.XcmFee.Mode.Proportional.weightToFee(weight: Weight): BigInteger {
    val pico = BigInteger.TEN.pow(12)

    // Weight is an amount of picoseconds operation suppose to execute
    return weight * unitsPerSecond / pico
}

fun LegacyCrossChainTransfersConfiguration.availableOutDestinations(origin: Chain.Asset): List<FullChainAssetId> {
    val assetTransfers = outComingAssetTransfers(origin) ?: return emptyList()

    return assetTransfers.xcmTransfers
        .filter { it.type != LegacyXcmTransferMethod.UNKNOWN }
        .map { it.destination.fullDestinationAssetId }
}

fun LegacyCrossChainTransfersConfiguration.availableInDestinations(destination: Chain.Asset): List<FullChainAssetId> {
    val requiredDestinationId = destination.fullId

    return chains.flatMap { (originChainId, chainTransfers) ->
        chainTransfers.mapNotNull { originAssetTransfers ->
            val hasDestination = originAssetTransfers.xcmTransfers
                .any { it.type != LegacyXcmTransferMethod.UNKNOWN && it.destination.fullDestinationAssetId == requiredDestinationId }

            FullChainAssetId(originChainId, originAssetTransfers.assetId).takeIf { hasDestination }
        }
    }
}

fun LegacyCrossChainTransfersConfiguration.availableInDestinations(): List<Edge<FullChainAssetId>> {
    return chains.flatMap { (originChainId, chainTransfers) ->
        chainTransfers.flatMap { originAssetTransfers ->
            originAssetTransfers.xcmTransfers.mapNotNull {
                if (it.type == LegacyXcmTransferMethod.UNKNOWN) return@mapNotNull null

                val from = FullChainAssetId(originChainId, originAssetTransfers.assetId)
                val to = FullChainAssetId(it.destination.chainId, it.destination.assetId)

                SimpleEdge(from, to)
            }
        }
    }
}

suspend fun LegacyCrossChainTransfersConfiguration.transferConfiguration(
    originXcmChain: XcmChain,
    originAsset: Chain.Asset,
    destinationXcmChain: XcmChain,
): LegacyCrossChainTransferConfiguration? {
    val originChain = originXcmChain.chain
    val destinationChain = destinationXcmChain.chain

    val assetTransfers = outComingAssetTransfers(originAsset) ?: return null
    val destination = assetTransfers.xcmTransfers.find { it.destination.chainId == destinationChain.id } ?: return null

    val reserveAssetLocation = assetLocations.getValue(assetTransfers.assetLocation)
    val hasReserveFee = reserveAssetLocation.chainId !in setOf(originChain.id, destinationChain.id)
    val reserveFee = if (hasReserveFee) {
        // reserve fee must be present if there is at least one non-reserve transfer
        matchInstructions(reserveAssetLocation.reserveFee!!, originChain.id, reserveAssetLocation.chainId)
    } else {
        null
    }

    val destinationFee = matchInstructions(
        destination.destination.fee,
        if (hasReserveFee) reserveAssetLocation.chainId else originChain.id,
        destination.destination.chainId
    )

    return LegacyCrossChainTransferConfiguration(
        originChain = originXcmChain,
        destinationChain = destinationXcmChain,
        transferType = XcmReserveTransferType.determineTransferType(
            usesTeleports = XcmReserveTransferType.isSystemTeleport(originXcmChain, destinationXcmChain),
            originChain = originXcmChain,
            destinationChain = destinationXcmChain,
            reserve = reserveRegistry.getReserve(originAsset)
        ),
        assetLocation = originAssetLocationOf(assetTransfers),
        reserveChainLocation = reserveAssetLocation.multiLocation,
        destinationChainLocation = destinationLocation(originChain, destinationXcmChain.parachainId, destinationChain.id),
        destinationFee = destinationFee,
        reserveFee = reserveFee,
        originChainAsset = originAsset,
        transferMethod = destination.type
    )
}

fun LegacyCrossChainTransfersConfiguration.hasDeliveryFee(chainId: ChainId): Boolean {
    return chainId in deliveryFeeConfigurations
}

private fun LegacyCrossChainTransfersConfiguration.matchInstructions(
    xcmFee: LegacyCrossChainTransfersConfiguration.XcmFee<String>,
    fromChainId: ChainId,
    toChainId: ChainId,
): CrossChainFeeConfiguration {
    return CrossChainFeeConfiguration(
        from = CrossChainFeeConfiguration.From(
            chainId = fromChainId,
            deliveryFeeConfiguration = deliveryFeeConfigurations[fromChainId],
        ),
        to = CrossChainFeeConfiguration.To(
            chainId = toChainId,
            instructionWeight = instructionBaseWeights.getValue(toChainId),
            xcmFeeType = LegacyCrossChainTransfersConfiguration.XcmFee(
                mode = xcmFee.mode,
                instructions = feeInstructions.getValue(xcmFee.instructions),
            )
        )
    )
}

private fun destinationLocation(
    originChain: Chain,
    destinationParaId: ParaId?,
    destinationChainId: ChainId
) = when {
    // parachain -> parachain
    originChain.isParachain && destinationParaId != null -> SiblingParachain(destinationParaId, destinationChainId)

    // parachain -> relaychain
    originChain.isParachain -> ParentChain()

    // relaychain -> parachain
    destinationParaId != null -> ChildParachain(destinationParaId, destinationChainId)

    // relaychain -> relaychain ?
    else -> throw UnsupportedOperationException("Unsupported cross-chain transfer")
}

private fun ChildParachain(paraId: ParaId, destinationChainId: ChainId): RelativeMultiLocation {
    val junctionTypeName = junctionTypeNameForChain(destinationChainId)
    return RelativeMultiLocation(
        parents = 0,
        interior = listOf(Junction.ParachainId(paraId, junctionTypeName)).toInterior()
    )
}

private fun ParentChain(): RelativeMultiLocation {
    return RelativeMultiLocation(
        parents = 1,
        interior = Interior.Here
    )
}

private fun SiblingParachain(paraId: ParaId, destinationChainId: ChainId): RelativeMultiLocation {
    val junctionTypeName = junctionTypeNameForChain(destinationChainId)
    return RelativeMultiLocation(
        parents = 1,
        listOf(Junction.ParachainId(paraId, junctionTypeName)).toInterior()
    )
}

private fun LegacyCrossChainTransfersConfiguration.originAssetLocationOf(assetTransfers: AssetTransfers): RelativeMultiLocation {
    return when (val path = assetTransfers.assetLocationPath) {
        is AssetLocationPath.Absolute -> assetLocations.getValue(assetTransfers.assetLocation).multiLocation.childView()
        is AssetLocationPath.Relative -> assetLocations.getValue(assetTransfers.assetLocation).multiLocation.localView()
        is AssetLocationPath.Concrete -> path.multiLocation
    }
}

private fun LegacyCrossChainTransfersConfiguration.outComingAssetTransfers(origin: Chain.Asset): AssetTransfers? {
    return chains[origin.chainId]?.find { it.assetId == origin.id }
}

private val LegacyCrossChainTransfersConfiguration.XcmDestination.fullDestinationAssetId: FullChainAssetId
    get() = FullChainAssetId(chainId, assetId)

private fun RelativeMultiLocation.localView(): RelativeMultiLocation {
    return RelativeMultiLocation(
        parents = 0,
        interior = when (val interior = interior) {
            Interior.Here -> interior

            is Interior.Junctions -> interior.junctions.takeLastWhile { it !is Junction.ParachainId }
                .toInterior()
        }
    )
}

private fun RelativeMultiLocation.childView() = RelativeMultiLocation(parents + 1, interior)
