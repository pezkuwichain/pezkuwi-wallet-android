package io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats

import io.novafoundation.nova.common.data.config.GlobalConfigDataSource
import io.novafoundation.nova.common.data.network.subquery.SubQueryNodes
import io.novafoundation.nova.common.domain.config.GlobalConfig
import io.novafoundation.nova.common.utils.asPerbill
import io.novafoundation.nova.common.utils.atLeastZero
import io.novafoundation.nova.common.utils.orZero
import io.novafoundation.nova.common.utils.removeHexPrefix
import io.novafoundation.nova.common.utils.retryUntilDone
import io.novafoundation.nova.common.utils.toPercent
import io.novafoundation.nova.feature_staking_api.domain.dashboard.model.StakingOptionId
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.api.StakingStatsApi
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.api.StakingStatsRequest
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.api.StakingStatsResponse
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.api.StakingStatsResponse.AccumulatedReward
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.api.StakingStatsResponse.WithStakingId
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.api.StakingStatsRewards
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.api.mapSubQueryIdToStakingType
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.runtime.ext.UTILITY_ASSET_ID
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

interface StakingStatsDataSource {

    suspend fun fetchStakingStats(stakingAccounts: StakingAccounts, stakingChains: List<Chain>): MultiChainStakingStats
}

class RealStakingStatsDataSource(
    private val api: StakingStatsApi,
    private val globalConfigDataSource: GlobalConfigDataSource
) : StakingStatsDataSource {

    override suspend fun fetchStakingStats(
        stakingAccounts: StakingAccounts,
        stakingChains: List<Chain>
    ): MultiChainStakingStats = withContext(Dispatchers.IO) {
        retryUntilDone(maxAttempts = 5) {
            val globalConfig = globalConfigDataSource.getGlobalConfig()
            val chainsByEndpoint = splitChainsByEndpoint(stakingChains, globalConfig)

            coroutineScope {
                chainsByEndpoint.map { (url, chains) ->
                    async { fetchStatsFromEndpoint(stakingAccounts, chains, url) }
                }.map { it.await() }
            }.fold(emptyMap()) { acc, map -> acc + map }
        }
    }

    private suspend fun fetchStatsFromEndpoint(
        stakingAccounts: StakingAccounts,
        chains: List<Chain>,
        url: String
    ): MultiChainStakingStats {
        if (chains.isEmpty()) return emptyMap()

        val request = StakingStatsRequest(stakingAccounts, chains)
        val response = api.fetchStakingStats(request, url).data

        val earnings = response.stakingApies.associatedById()
        val rewards = response.rewards?.associatedById() ?: emptyMap()
        val slashes = response.slashes?.associatedById() ?: emptyMap()
        val activeStakers = response.activeStakers?.groupedById() ?: emptyMap()

        return request.stakingKeysMapping.mapValues { (originalStakingOptionId, stakingKeys) ->
            val totalReward = rewards.getPlanks(originalStakingOptionId) - slashes.getPlanks(originalStakingOptionId)

            val stakingStatusAddress = stakingKeys.stakingStatusAddress
            val stakingOptionActiveStakers = activeStakers[stakingKeys.stakingStatusOptionId].orEmpty()
            val isStakingActive = stakingStatusAddress != null && stakingStatusAddress in stakingOptionActiveStakers

            ChainStakingStats(
                estimatedEarnings = earnings[originalStakingOptionId]?.maxAPY.orZero().asPerbill().toPercent(),
                accountPresentInActiveStakers = isStakingActive,
                rewards = totalReward.atLeastZero()
            )
        }
    }

    private fun splitChainsByEndpoint(
        chains: List<Chain>,
        globalConfig: GlobalConfig
    ): Map<String, List<Chain>> {
        val overrideUrlByChainId = globalConfig.stakingApiOverrides.flatMap { (url, chainIds) ->
            chainIds.map { chainId -> chainId to url }
        }.toMap()

        val result = mutableMapOf<String, MutableList<Chain>>()
        for (chain in chains) {
            val url = overrideUrlByChainId[chain.id] ?: globalConfig.multiStakingApiUrl
            result.getOrPut(url) { mutableListOf() }.add(chain)
        }
        return result
    }

    private fun Map<StakingOptionId, AccumulatedReward>.getPlanks(key: StakingOptionId): Balance {
        return get(key)?.amount?.toBigInteger().orZero()
    }

    private fun <T : WithStakingId> SubQueryNodes<T>.associatedById(): Map<StakingOptionId, T> {
        return nodes.associateBy {
            StakingOptionId(
                chainId = it.networkId.removeHexPrefix(),
                chainAssetId = UTILITY_ASSET_ID,
                stakingType = mapSubQueryIdToStakingType(it.stakingType)
            )
        }
    }

    private fun SubQueryNodes<StakingStatsResponse.ActiveStaker>.groupedById(): Map<StakingOptionId, List<String>> {
        return nodes.groupBy(
            keySelector = {
                StakingOptionId(
                    chainId = it.networkId.removeHexPrefix(),
                    chainAssetId = UTILITY_ASSET_ID,
                    stakingType = mapSubQueryIdToStakingType(it.stakingType)
                )
            },
            valueTransform = { it.address }
        )
    }

    private fun StakingStatsRewards.associatedById(): Map<StakingOptionId, AccumulatedReward> {
        return groupedAggregates.associateBy(
            keySelector = { rewardAggregate ->
                val (networkId, stakingTypeRaw) = rewardAggregate.keys

                StakingOptionId(
                    chainId = networkId.removeHexPrefix(),
                    chainAssetId = UTILITY_ASSET_ID,
                    stakingType = mapSubQueryIdToStakingType(stakingTypeRaw)
                )
            },
            valueTransform = { rewardAggregate -> rewardAggregate.sum }
        )
    }
}
