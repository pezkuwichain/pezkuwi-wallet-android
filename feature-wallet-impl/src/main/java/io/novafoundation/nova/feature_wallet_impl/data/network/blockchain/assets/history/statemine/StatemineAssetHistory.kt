package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.statemine

import io.novafoundation.nova.common.data.network.runtime.binding.bindAccountIdentifier
import io.novafoundation.nova.common.data.network.runtime.binding.bindNumber
import io.novafoundation.nova.common.utils.instanceOf
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.history.realtime.RealtimeHistoryUpdate
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.history.realtime.substrate.SubstrateRealtimeOperationFetcher
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.history.realtime.substrate.SubstrateRealtimeOperationFetcher.Factory.Source
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.history.realtime.substrate.asSource
import io.novafoundation.nova.feature_wallet_api.data.repository.CoinPriceRepository
import io.novafoundation.nova.feature_wallet_api.domain.interfaces.TransactionFilter
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.SubstrateAssetHistory
import io.novafoundation.nova.feature_wallet_impl.data.network.subquery.SubQueryOperationsApi
import io.novafoundation.nova.feature_wallet_impl.data.storage.TransferCursorStorage
import io.novafoundation.nova.runtime.ext.assetConversionSupported
import io.novafoundation.nova.runtime.ext.isSwapSupported
import io.novafoundation.nova.runtime.ext.isUtilityAsset
import io.novafoundation.nova.runtime.ext.palletNameOrDefault
import io.novafoundation.nova.runtime.ext.requireStatemine
import io.novafoundation.nova.runtime.extrinsic.visitor.extrinsic.api.ExtrinsicVisit
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.hasSameId
import io.novafoundation.nova.runtime.multiNetwork.getRuntime

class StatemineAssetHistory(
    private val chainRegistry: ChainRegistry,
    realtimeOperationFetcherFactory: SubstrateRealtimeOperationFetcher.Factory,
    walletOperationsApi: SubQueryOperationsApi,
    cursorStorage: TransferCursorStorage,
    coinPriceRepository: CoinPriceRepository
) : SubstrateAssetHistory(walletOperationsApi, cursorStorage, realtimeOperationFetcherFactory, coinPriceRepository) {

    override fun realtimeFetcherSources(chain: Chain): List<Source> {
        return buildList {
            add(TransferExtractor().asSource())

            if (chain.swap.assetConversionSupported()) {
                add(Source.Known.Id.ASSET_CONVERSION_SWAP.asSource())
            }
        }
    }

    override fun availableOperationFilters(chain: Chain, asset: Chain.Asset): Set<TransactionFilter> {
        return setOfNotNull(
            TransactionFilter.TRANSFER,
            TransactionFilter.EXTRINSIC.takeIf { asset.isUtilityAsset },
            TransactionFilter.SWAP.takeIf { chain.isSwapSupported() }
        )
    }

    private inner class TransferExtractor : SubstrateRealtimeOperationFetcher.Extractor {

        override suspend fun extractRealtimeHistoryUpdates(
            extrinsicVisit: ExtrinsicVisit,
            chain: Chain,
            chainAsset: Chain.Asset
        ): RealtimeHistoryUpdate.Type? {
            val runtime = chainRegistry.getRuntime(chain.id)

            val call = extrinsicVisit.call
            val statemineType = chainAsset.requireStatemine()
            val moduleName = statemineType.palletNameOrDefault()

            val (senderId, recipientId) = call.assetTransferParties(moduleName, extrinsicVisit.origin) ?: return null

            if (!statemineType.hasSameId(runtime, dynamicInstanceId = call.arguments["id"])) return null

            return RealtimeHistoryUpdate.Type.Transfer(
                senderId = senderId,
                recipientId = recipientId,
                amountInPlanks = bindNumber(call.arguments["amount"]),
                chainAsset = chainAsset,
            )
        }
    }
}

/** Sender and recipient of an assets pallet transfer, or null when the call is not one. */
internal fun GenericCall.Instance.assetTransferParties(moduleName: String, origin: AccountId): Pair<AccountId, AccountId>? = when {
    instanceOf(moduleName, "transfer", "transfer_keep_alive") ->
        origin to bindAccountIdentifier(arguments["target"])

    // Spends an allowance: the funds leave `owner`, not the delegate that signed (the USDT bridge credits this way)
    instanceOf(moduleName, "transfer_approved") ->
        bindAccountIdentifier(arguments["owner"]) to bindAccountIdentifier(arguments["destination"])

    else -> null
}
