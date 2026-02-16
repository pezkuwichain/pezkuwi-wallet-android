package io.novafoundation.nova.feature_assets.data.repository

import io.novafoundation.nova.common.data.network.runtime.binding.bindInt
import io.novafoundation.nova.common.data.network.runtime.binding.bindNumber
import io.novafoundation.nova.common.data.network.runtime.binding.castToDictEnum
import io.novafoundation.nova.common.data.network.runtime.binding.castToList
import io.novafoundation.nova.common.data.network.runtime.binding.castToStruct
import io.novafoundation.nova.feature_assets.data.model.PezkuwiDashboardData
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.definitions.types.composite.DictEnum
import io.novasama.substrate_sdk_android.runtime.metadata.moduleOrNull
import io.novasama.substrate_sdk_android.runtime.metadata.storage
import java.math.BigInteger

class PezkuwiDashboardRepository(
    private val remoteStorageDataSource: StorageDataSource
) {

    suspend fun getDashboard(accountId: AccountId): PezkuwiDashboardData {
        val chainId = ChainGeneses.PEZKUWI_PEOPLE

        val roles = queryRoles(chainId, accountId)
        val trustScore = queryTrustScore(chainId, accountId)
        val totalReferrals = queryReferrals(chainId, accountId)
        val stakedAmount = queryStakedAmount(chainId, accountId)
        val perwerdePoints = queryPerwerdePoints(chainId, accountId)

        return PezkuwiDashboardData(
            roles = roles,
            trustScore = trustScore,
            totalReferrals = totalReferrals,
            stakedAmount = stakedAmount,
            perwerdePoints = perwerdePoints
        )
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

    private suspend fun queryReferrals(chainId: String, accountId: AccountId): Int = runCatching {
        remoteStorageDataSource.query(chainId) {
            val referralModule = runtime.metadata.moduleOrNull("Referral") ?: return@query 0
            referralModule.storage("ReferrerStatsStorage").query(accountId, binding = { decoded ->
                decoded?.castToStruct()?.let { struct ->
                    bindInt(struct["total_referrals"])
                } ?: 0
            })
        }
    }.getOrDefault(0)

    private suspend fun queryStakedAmount(chainId: String, accountId: AccountId): BigInteger = runCatching {
        remoteStorageDataSource.query(chainId) {
            val stakingModule = runtime.metadata.moduleOrNull("StakingScore") ?: return@query BigInteger.ZERO

            val relayChainKey = DictEnum.Entry("RelayChain", null)
            val assetHubKey = DictEnum.Entry("AssetHub", null)

            val relayStaked = runCatching {
                stakingModule.storage("CachedStakingDetails").query(accountId, relayChainKey, binding = { decoded ->
                    decoded?.castToStruct()?.let { struct ->
                        bindNumber(struct["staked_amount"])
                    } ?: BigInteger.ZERO
                })
            }.getOrDefault(BigInteger.ZERO)

            val assetHubStaked = runCatching {
                stakingModule.storage("CachedStakingDetails").query(accountId, assetHubKey, binding = { decoded ->
                    decoded?.castToStruct()?.let { struct ->
                        bindNumber(struct["staked_amount"])
                    } ?: BigInteger.ZERO
                })
            }.getOrDefault(BigInteger.ZERO)

            relayStaked.add(assetHubStaked)
        }
    }.getOrDefault(BigInteger.ZERO)

    private suspend fun queryPerwerdePoints(chainId: String, accountId: AccountId): Int = runCatching {
        remoteStorageDataSource.query(chainId) {
            val perwerdeModule = runtime.metadata.moduleOrNull("Perwerde") ?: return@query 0

            val courseIds = perwerdeModule.storage("StudentCourses").query(accountId, binding = { decoded ->
                decoded?.castToList()?.map { bindInt(it) } ?: emptyList()
            })

            if (courseIds.isEmpty()) return@query 0

            courseIds.sumOf { courseId ->
                runCatching {
                    perwerdeModule.storage("Enrollments").query(courseId, accountId, binding = { decoded ->
                        decoded?.castToStruct()?.let { struct ->
                            bindInt(struct["points_earned"])
                        } ?: 0
                    })
                }.getOrDefault(0)
            }
        }
    }.getOrDefault(0)
}
