package io.novafoundation.nova.feature_dapp_impl.data.repository

import io.novafoundation.nova.core_db.dao.BrowserHostSettingsDao
import io.novafoundation.nova.feature_dapp_impl.data.network.metadata.DappMetadataApi
import io.novafoundation.nova.feature_dapp_impl.data.network.metadata.DappMetadataRemote
import io.novafoundation.nova.feature_dapp_impl.data.network.metadata.DappMetadataResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import java.io.IOException

@RunWith(MockitoJUnitRunner::class)
class RealDAppMetadataRepositoryTest {

    @Mock
    lateinit var hostSettingsDao: BrowserHostSettingsDao

    private val bundled = catalogOf("https://bundled.example")
    private val remote = catalogOf("https://remote.example")

    @Test
    fun `a first install shows the bundled catalog until one is fetched`() = runBlocking {
        `when`(hostSettingsDao.getBrowserAllHostSettings()).thenReturn(emptyList())
        val network = CompletableDeferred<DappMetadataResponse>()
        var calls = 0
        val api = object : DappMetadataApi {
            override suspend fun getParachainMetadata(url: String): DappMetadataResponse {
                calls++
                if (calls == 1) throw IOException("unreachable")
                return network.await()
            }
        }
        val repository = RealDAppMetadataRepository(api, "url", hostSettingsDao) { bundled }

        val sync = launch { repository.syncDAppMetadatas() }
        while (!repository.isDAppsSynced()) yield()

        assertEquals("https://bundled.example", repository.getDAppCatalog().dApps.single().url)

        network.complete(remote)
        sync.join()

        assertEquals("https://remote.example", repository.getDAppCatalog().dApps.single().url)
    }

    @Test
    fun `the bundled catalog is not read when the fetch works`() = runBlocking {
        `when`(hostSettingsDao.getBrowserAllHostSettings()).thenReturn(emptyList())
        var bundledReads = 0
        val api = object : DappMetadataApi {
            override suspend fun getParachainMetadata(url: String) = remote
        }
        val repository = RealDAppMetadataRepository(api, "url", hostSettingsDao) { bundledReads++; bundled }

        repository.syncDAppMetadatas()

        assertEquals(0, bundledReads)
        assertEquals("https://remote.example", repository.getDAppCatalog().dApps.single().url)
    }

    private fun catalogOf(url: String) = DappMetadataResponse(
        popular = emptyList(),
        categories = emptyList(),
        dapps = listOf(DappMetadataRemote(name = "d", icon = "i", url = url, categories = emptyList(), desktopOnly = null))
    )
}
