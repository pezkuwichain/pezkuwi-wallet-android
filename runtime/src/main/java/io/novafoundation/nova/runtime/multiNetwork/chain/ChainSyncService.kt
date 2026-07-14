package io.novafoundation.nova.runtime.multiNetwork.chain

import android.util.Log
import com.google.gson.Gson
import io.novafoundation.nova.common.utils.CollectionDiffer
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.common.utils.retryUntilDone
import io.novafoundation.nova.core_db.dao.ChainDao
import io.novafoundation.nova.core_db.dao.FullAssetIdLocal
import io.novafoundation.nova.core_db.ext.fullId
import io.novafoundation.nova.core_db.model.chain.AssetSourceLocal
import io.novafoundation.nova.core_db.model.chain.ChainAssetLocal.Companion.ENABLED_DEFAULT_BOOL
import io.novafoundation.nova.core_db.model.chain.ChainLocal
import io.novafoundation.nova.core_db.model.chain.ChainNodeLocal
import io.novafoundation.nova.core_db.model.chain.NodeSelectionPreferencesLocal
import io.novafoundation.nova.runtime.multiNetwork.chain.mappers.mapExternalApisToLocal
import io.novafoundation.nova.runtime.multiNetwork.chain.mappers.mapRemoteAssetToLocal
import io.novafoundation.nova.runtime.multiNetwork.chain.mappers.mapRemoteChainToLocal
import io.novafoundation.nova.runtime.multiNetwork.chain.mappers.mapRemoteExplorersToLocal
import io.novafoundation.nova.runtime.multiNetwork.chain.mappers.mapRemoteNodesToLocal
import io.novafoundation.nova.runtime.multiNetwork.chain.remote.ChainFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChainSyncService(
    private val chainDao: ChainDao,
    private val chainFetcher: ChainFetcher,
    private val gson: Gson
) {

    suspend fun syncUp() = withContext(Dispatchers.Default) {
        val localChainsJoinedInfo = chainDao.getJoinChainInfo().filter { it.chain.source != ChainLocal.Source.CUSTOM }
        val oldChains = localChainsJoinedInfo.map { it.chain }
        val oldAssets = localChainsJoinedInfo.flatMap { it.assets }.filter { it.source == AssetSourceLocal.DEFAULT }
        val oldNodes = localChainsJoinedInfo.flatMap { it.nodes }.filter { it.source != ChainNodeLocal.Source.CUSTOM }
        val oldExplorers = localChainsJoinedInfo.flatMap { it.explorers }
        val oldExternalApis = localChainsJoinedInfo.flatMap { it.externalApis }
        val oldNodeSelectionPreferences = localChainsJoinedInfo.mapNotNull { it.nodeSelectionPreferences }

        val oldChainsById = oldChains.associateBy { it.id }
        val associatedOldAssets = oldAssets.associateBy { it.fullId() }

        val remoteChains = retryUntilDone { chainFetcher.getChains() }

        // A transient upstream issue (CDN hiccup, regional network filtering, a bad publish) can make
        // chainFetcher.getChains() return successfully with a suspiciously small/empty list instead of
        // throwing. Applying that as a diff against a populated local DB would delete most or all of the
        // user's chains/assets - not a sync failure, but active data loss, for something that self-heals on
        // the next successful sync if we just skip applying it. Only guard when we HAD data: an empty result
        // on a genuinely first-ever sync is normal and must proceed.
        if (oldChains.isNotEmpty() && remoteChains.size < oldChains.size / 2) {
            Log.e(
                LOG_TAG,
                "Refusing to apply chain sync: remote returned ${remoteChains.size} chains vs ${oldChains.size} currently stored " +
                    "(would remove more than half). Likely a transient fetch issue - skipping this sync cycle."
            )
            return@withContext
        }

        // One malformed/incompatible chain (a new field the app's mapper doesn't understand yet, a bad
        // publish, etc.) must not take down sync for every other chain - a plain .map{} here means a single
        // throwing chain aborts before chainDao.applyDiff() is ever called, leaving a brand new install with
        // zero locally-cached chains forever (a completely empty tokens list), since nothing else in this
        // function ever gets a chance to run. Isolate failures per chain, and per asset within a chain that
        // otherwise mapped fine, instead.
        val remoteChainsWithLocal = remoteChains.mapNotNull { chainRemote ->
            runCatching { chainRemote to mapRemoteChainToLocal(chainRemote, oldChainsById[chainRemote.chainId], source = ChainLocal.Source.DEFAULT, gson) }
                .onFailure { Log.e(LOG_TAG, "Failed to map remote chain ${chainRemote.chainId} (${chainRemote.name}), skipping it for this sync cycle", it) }
                .getOrNull()
        }

        val newChains = remoteChainsWithLocal.map { (_, chainLocal) -> chainLocal }
        val newAssets = remoteChainsWithLocal.flatMap { (chain, _) ->
            chain.assets.mapNotNull { assetRemote ->
                runCatching {
                    val fullAssetId = FullAssetIdLocal(chain.chainId, assetRemote.assetId)
                    val oldAsset = associatedOldAssets[fullAssetId]
                    mapRemoteAssetToLocal(chain, assetRemote, gson, oldAsset?.enabled ?: ENABLED_DEFAULT_BOOL)
                }.onFailure {
                    Log.e(LOG_TAG, "Failed to map asset ${assetRemote.assetId} (${assetRemote.symbol}) on chain ${chain.chainId}, skipping it", it)
                }.getOrNull()
            }
        }
        val newNodes = remoteChainsWithLocal.flatMap { (chain, _) -> mapRemoteNodesToLocal(chain) }
        val newExplorers = remoteChainsWithLocal.flatMap { (chain, _) -> mapRemoteExplorersToLocal(chain) }
        val newExternalApis = remoteChainsWithLocal.flatMap { (chain, _) -> mapExternalApisToLocal(chain) }
        val newNodeSelectionPreferences = nodeSelectionPreferencesFor(newChains, oldNodeSelectionPreferences)

        val chainsDiff = CollectionDiffer.findDiff(newChains, oldChains, forceUseNewItems = false)
        val assetDiff = CollectionDiffer.findDiff(newAssets, oldAssets, forceUseNewItems = false)
        val nodesDiff = CollectionDiffer.findDiff(newNodes, oldNodes, forceUseNewItems = false)
        val explorersDiff = CollectionDiffer.findDiff(newExplorers, oldExplorers, forceUseNewItems = false)
        val externalApisDiff = CollectionDiffer.findDiff(newExternalApis, oldExternalApis, forceUseNewItems = false)
        val nodeSelectionPreferencesDiff = CollectionDiffer.findDiff(newNodeSelectionPreferences, oldNodeSelectionPreferences, forceUseNewItems = false)

        chainDao.applyDiff(
            chainDiff = chainsDiff,
            assetsDiff = assetDiff,
            nodesDiff = nodesDiff,
            explorersDiff = explorersDiff,
            externalApisDiff = externalApisDiff,
            nodeSelectionPreferencesDiff = nodeSelectionPreferencesDiff
        )
    }

    private fun nodeSelectionPreferencesFor(
        newChains: List<ChainLocal>,
        oldNodeSelectionPreferences: List<NodeSelectionPreferencesLocal>
    ): List<NodeSelectionPreferencesLocal> {
        val preferencesById = oldNodeSelectionPreferences.associateBy { it.chainId }
        return newChains.map {
            preferencesById[it.id]
                ?: NodeSelectionPreferencesLocal(
                    chainId = it.id,
                    autoBalanceEnabled = NodeSelectionPreferencesLocal.DEFAULT_AUTO_BALANCE_BOOLEAN,
                    selectedNodeUrl = null
                )
        }
    }
}
