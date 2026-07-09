package io.novafoundation.nova.runtime.multiNetwork.asset

import android.util.Log
import com.google.gson.Gson
import io.novafoundation.nova.common.utils.CollectionDiffer
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.common.utils.retryUntilDone
import io.novafoundation.nova.core_db.dao.ChainAssetDao
import io.novafoundation.nova.core_db.dao.ChainDao
import io.novafoundation.nova.core_db.ext.fullId
import io.novafoundation.nova.core_db.model.chain.AssetSourceLocal
import io.novafoundation.nova.core_db.model.chain.ChainAssetLocal.Companion.ENABLED_DEFAULT_BOOL
import io.novafoundation.nova.runtime.multiNetwork.asset.remote.AssetFetcher
import io.novafoundation.nova.runtime.multiNetwork.chain.mappers.mapEVMAssetRemoteToLocalAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EvmAssetsSyncService(
    private val chainDao: ChainDao,
    private val chainAssetDao: ChainAssetDao,
    private val chainFetcher: AssetFetcher,
    private val gson: Gson,
) {

    suspend fun syncUp() = withContext(Dispatchers.Default) {
        syncEVMAssets()
    }

    private suspend fun syncEVMAssets() {
        val availableChainIds = chainDao.getAllChainIds().toSet()

        val oldAssets = chainAssetDao.getAssetsBySource(AssetSourceLocal.ERC20)
        val associatedOldAssets = oldAssets.associateBy { it.fullId() }

        val newAssets = retryUntilDone { chainFetcher.getEVMAssets() }
            .flatMap { mapEVMAssetRemoteToLocalAssets(it, gson) }
            .mapNotNull { new ->
                // handle misconfiguration between chains.json and assets.json when assets contains asset for chain that is not present in chain
                if (new.chainId !in availableChainIds) return@mapNotNull null

                val old = associatedOldAssets[new.fullId()]
                new.copy(enabled = old?.enabled ?: ENABLED_DEFAULT_BOOL)
            }

        // Same defensive guard as ChainSyncService: a transient upstream issue can make the fetch return
        // successfully with a suspiciously small/empty list. Diffing that against a populated local DB would
        // delete most or all of the user's ERC20 tokens (e.g. USDT-ERC20) - skip instead of wiping good data.
        if (oldAssets.isNotEmpty() && newAssets.size < oldAssets.size / 2) {
            Log.e(
                LOG_TAG,
                "Refusing to apply EVM asset sync: remote returned ${newAssets.size} assets vs ${oldAssets.size} currently stored " +
                    "(would remove more than half). Likely a transient fetch issue - skipping this sync cycle."
            )
            return
        }

        val diff = CollectionDiffer.findDiff(newAssets, oldAssets, forceUseNewItems = false)
        chainAssetDao.updateAssets(diff)
    }
}
