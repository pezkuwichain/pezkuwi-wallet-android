package io.novafoundation.nova.feature_dapp_impl.data.repository

import android.util.Log
import io.novafoundation.nova.common.utils.CollectionDiffer
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.common.utils.Urls
import io.novafoundation.nova.common.utils.retryUntilDone
import io.novafoundation.nova.core_db.dao.BrowserHostSettingsDao
import io.novafoundation.nova.core_db.model.BrowserHostSettingsLocal
import io.novafoundation.nova.feature_dapp_api.data.model.DappCatalog
import io.novafoundation.nova.feature_dapp_api.data.model.DappMetadata
import io.novafoundation.nova.feature_dapp_api.data.repository.DAppMetadataRepository
import io.novafoundation.nova.feature_dapp_impl.data.mappers.mapDAppMetadataResponseToDAppMetadatas
import io.novafoundation.nova.feature_dapp_impl.data.network.metadata.DappMetadataApi
import io.novafoundation.nova.feature_dapp_impl.data.network.metadata.DappMetadataRemote
import io.novafoundation.nova.feature_dapp_impl.data.network.metadata.DappMetadataResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

class RealDAppMetadataRepository(
    private val dappMetadataApi: DappMetadataApi,
    private val remoteApiUrl: String,
    private val browserHostSettingsDao: BrowserHostSettingsDao,
    private val bundledCatalog: BundledDappCatalog = BundledDappCatalog.None,
) : DAppMetadataRepository {

    private val dappMetadatasFlow = MutableSharedFlow<DappCatalog>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun isDAppsSynced(): Boolean {
        return dappMetadatasFlow.replayCache.isNotEmpty()
    }

    override suspend fun syncDAppMetadatas() {
        if (dappMetadatasFlow.replayCache.isEmpty()) {
            // Every read below waits for a first catalog. Try the network once, and if that fails show the
            // catalog the APK was built with, so the DApps screen opens; the fetched one replaces it below.
            val firstTry = try {
                dappMetadataApi.getParachainMetadata(remoteApiUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "First DApps catalog fetch failed, showing the bundled catalog", e)
                null
            }

            if (firstTry != null) {
                applyCatalog(firstTry)
                return
            }

            bundledCatalog.load()?.let { dappMetadatasFlow.emit(mapDAppMetadataResponseToDAppMetadatas(it)) }
        }

        applyCatalog(retryUntilDone { dappMetadataApi.getParachainMetadata(remoteApiUrl) })
    }

    private suspend fun applyCatalog(response: DappMetadataResponse) {
        val dappMetadatas = mapDAppMetadataResponseToDAppMetadatas(response)
        syncHostSettings(response.dapps)
        dappMetadatasFlow.emit(dappMetadatas)
    }

    override suspend fun syncAndGetDapp(baseUrl: String): DappMetadata? {
        syncDAppMetadatas()

        return getDAppMetadata(baseUrl)
    }

    override suspend fun getDAppMetadata(baseUrl: String): DappMetadata? {
        return dappMetadatasFlow.first()
            .dApps
            .find { it.baseUrl == baseUrl }
    }

    override suspend fun findDAppMetadataByExactUrlMatch(fullUrl: String): DappMetadata? {
        return dappMetadatasFlow.first()
            .dApps
            .find { it.url == fullUrl }
    }

    override suspend fun findDAppMetadatasByBaseUrlMatch(baseUrl: String): List<DappMetadata> {
        return dappMetadatasFlow.first()
            .dApps
            .filter { it.baseUrl == baseUrl }
    }

    override suspend fun getDAppCatalog(): DappCatalog {
        return dappMetadatasFlow.first()
    }

    override fun observeDAppCatalog(): Flow<DappCatalog> {
        return dappMetadatasFlow
    }

    private suspend fun syncHostSettings(dappMetadatas: List<DappMetadataRemote>) {
        val oldSettings = browserHostSettingsDao.getBrowserAllHostSettings()
        val newSettings = dappMetadatas
            .filter { it.desktopOnly != null }
            .map { BrowserHostSettingsLocal(Urls.normalizeUrl(it.url), it.desktopOnly!!) }
        val differ = CollectionDiffer.findDiff(newSettings, oldSettings, false)
        browserHostSettingsDao.insertBrowserHostSettings(differ.added)
    }
}
