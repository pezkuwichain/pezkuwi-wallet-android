package io.novafoundation.nova.feature_dapp_impl.domain.search

import io.novafoundation.nova.feature_dapp_api.data.model.DappCatalog
import io.novafoundation.nova.feature_dapp_api.data.repository.DAppMetadataRepository
import io.novafoundation.nova.feature_dapp_impl.data.model.FavouriteDApp
import io.novafoundation.nova.feature_dapp_impl.data.repository.FavouritesDAppRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class SearchDappInteractorTest {

    private val catalog = object : DAppMetadataRepository by mock(DAppMetadataRepository::class.java) {
        override suspend fun getDAppCatalog() = DappCatalog(popular = emptyList(), categories = emptyList(), dApps = emptyList())
    }

    private val favourites = object : FavouritesDAppRepository by mock(FavouritesDAppRepository::class.java) {
        override suspend fun getFavourites() = emptyList<FavouriteDApp>()
    }

    private val interactor = SearchDappInteractor(catalog, favourites)

    @Test
    fun `a kurd name is never offered as a web address or a search`() = runBlocking {
        listOf("dks.kurd", "Serok.Gov.Kurd", "https://dks.kurd/path", "dks_news.kurd", "uni-x--edu.kurd").forEach { query ->
            val results = interactor.searchDapps(query, categoryId = null)

            assertEquals(query, null, results[DappSearchGroup.SEARCH])
        }
    }
}
