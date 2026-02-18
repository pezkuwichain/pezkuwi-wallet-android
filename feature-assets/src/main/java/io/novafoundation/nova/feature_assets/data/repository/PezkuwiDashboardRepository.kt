package io.novafoundation.nova.feature_assets.data.repository

import io.novafoundation.nova.common.data.network.runtime.binding.bindNumber
import io.novafoundation.nova.common.data.network.runtime.binding.castToDictEnum
import io.novafoundation.nova.common.data.network.runtime.binding.castToList
import io.novafoundation.nova.feature_assets.data.model.PezkuwiDashboardData
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.metadata.moduleOrNull
import io.novasama.substrate_sdk_android.runtime.metadata.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigInteger
import java.net.URL

class PezkuwiDashboardRepository(
    private val remoteStorageDataSource: StorageDataSource
) {

    companion object {
        private const val WELATI_COUNTER_URL = "https://telegram.pezkiwi.app/kurds"
    }

    suspend fun getDashboard(accountId: AccountId): PezkuwiDashboardData {
        val chainId = ChainGeneses.PEZKUWI_PEOPLE

        val roles = queryRoles(chainId, accountId)
        val trustScore = queryTrustScore(chainId, accountId)
        val welatiCount = fetchWelatiCount()

        return PezkuwiDashboardData(
            roles = roles.ifEmpty { listOf("Non-Citizen") },
            trustScore = trustScore,
            welatiCount = welatiCount
        )
    }

    private suspend fun fetchWelatiCount(): Int = withContext(Dispatchers.IO) {
        runCatching {
            val response = URL(WELATI_COUNTER_URL).readText()
            JSONObject(response).getInt("count")
        }.getOrDefault(0)
    }

    private suspend fun queryRoles(chainId: String, accountId: AccountId): List<String> = runCatching {
        remoteStorageDataSource.query(chainId) {
            val tikiModule = runtime.metadata.moduleOrNull("Tiki") ?: return@query emptyList()
            val result = tikiModule.storage("UserTikis").query(accountId, binding = { decoded ->
                decoded?.castToList()?.map { entry ->
                    entry!!.castToDictEnum().name
                } ?: emptyList()
            })
            result
        }
    }.getOrDefault(emptyList())

    private suspend fun queryTrustScore(chainId: String, accountId: AccountId): BigInteger = runCatching {
        remoteStorageDataSource.query(chainId) {
            val trustModule = runtime.metadata.moduleOrNull("Trust") ?: return@query BigInteger.ZERO
            trustModule.storage("TrustScores").query(accountId, binding = { decoded ->
                decoded?.let { bindNumber(it) } ?: BigInteger.ZERO
            })
        }
    }.getOrDefault(BigInteger.ZERO)
}
