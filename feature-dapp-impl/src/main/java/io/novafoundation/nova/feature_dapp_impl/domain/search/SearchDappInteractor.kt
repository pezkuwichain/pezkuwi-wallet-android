package io.novafoundation.nova.feature_dapp_impl.domain.search

import io.novafoundation.nova.common.list.GroupedList
import io.novafoundation.nova.common.utils.Urls
import io.novafoundation.nova.feature_dapp_api.data.model.DApp
import io.novafoundation.nova.feature_dapp_api.data.model.DappCategory
import io.novafoundation.nova.feature_dapp_api.data.repository.DAppMetadataRepository
import io.novafoundation.nova.feature_dapp_impl.data.repository.FavouritesDAppRepository
import io.novafoundation.nova.feature_dapp_impl.domain.common.buildUrlToDappMapping
import io.novafoundation.nova.feature_dapp_impl.domain.common.createDAppComparator
import io.novafoundation.nova.runtime.names.KurdName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class SearchDappInteractor(
    private val dAppMetadataRepository: DAppMetadataRepository,
    private val favouritesDAppRepository: FavouritesDAppRepository,
) {

    fun categories(): Flow<List<DappCategory>> {
        return dAppMetadataRepository.observeDAppCatalog()
            .map { it.categories }
    }

    suspend fun searchDapps(query: String, categoryId: String?): GroupedList<DappSearchGroup, DappSearchResult> = withContext(Dispatchers.Default) {
        val dapps = getDapps(categoryId)

        val dappsGroupContent = dapps
            .filter { query.isEmpty() || query.lowercase() in it.name.lowercase() }
            .sortedWith(createDAppComparator())
            .map(DappSearchResult::Dapp)

        // A .kurd name is resolved on chain by the wallet: never a web address, never a search query
        val kurdAddress = KurdName.parseAddress(query).takeIf { KurdName.mentionsKurd(query) }

        val searchGroupContent = when {
            query.isEmpty() -> null
            KurdName.mentionsKurd(query) -> null
            Urls.isValidWebUrl(query) -> {
                val searchUrl = Urls.ensureHttpsProtocol(query)
                val searchUrlDomain = Urls.domainOf(searchUrl)
                val trusting = dapps.any { Urls.domainOf(it.url) == searchUrlDomain }
                DappSearchResult.Url(searchUrl, trusting)
            }

            else -> DappSearchResult.Search(query, searchUrlFor(query))
        }

        buildMap {
            kurdAddress?.let { (name, path) ->
                put(DappSearchGroup.KURD_NAME, listOf(DappSearchResult.KurdName(name, path)))
            }

            searchGroupContent?.let {
                put(DappSearchGroup.SEARCH, listOf(searchGroupContent))
            }

            if (dappsGroupContent.isNotEmpty()) {
                put(DappSearchGroup.DAPPS, dappsGroupContent)
            }
        }
    }

    private fun searchUrlFor(query: String): String = "https://duckduckgo.com/?q=" + URLEncoder.encode(query, Charsets.UTF_8.name())

    private suspend fun getDapps(categoryId: String?): Collection<DApp> {
        val dApps = dAppMetadataRepository.getDAppCatalog()
            .dApps
            .filter { dapp -> categoryId == null || dapp.categories.any { it.id == categoryId } }
            .associateBy { it.url }
        val favouriteDApps = favouritesDAppRepository.getFavourites()
            .filter { categoryId == null || it.url in dApps.keys }

        val dAppByUrlMapping = buildUrlToDappMapping(dApps.values, favouriteDApps)
        return dAppByUrlMapping.values
    }
}
