package io.novafoundation.nova.feature_staking_impl.domain.common

import io.novafoundation.nova.common.data.memory.ComputationalCache
import io.novafoundation.nova.feature_account_api.data.model.AccountIdMap
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_staking_api.domain.api.ExposuresWithEraIndex
import io.novafoundation.nova.feature_staking_api.domain.api.StakingRepository
import io.novafoundation.nova.feature_staking_api.domain.model.EraIndex
import io.novafoundation.nova.feature_staking_api.domain.model.Exposure
import io.novafoundation.nova.feature_staking_api.domain.model.relaychain.StakingState
import io.novafoundation.nova.feature_staking_impl.data.StakingOption
import io.novafoundation.nova.feature_staking_impl.data.createStakingOption
import io.novafoundation.nova.feature_staking_impl.data.repository.BagListRepository
import io.novafoundation.nova.feature_staking_impl.data.repository.StakingConstantsRepository
import io.novafoundation.nova.feature_staking_impl.data.repository.bagListLocatorOrNull
import io.novafoundation.nova.feature_staking_impl.domain.bagList.BagListScoreConverter
import io.novafoundation.nova.feature_staking_impl.domain.minimumStake
import io.novafoundation.nova.feature_staking_impl.domain.rewards.RewardCalculator
import io.novafoundation.nova.feature_staking_impl.domain.rewards.RewardCalculatorFactory
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.runtime.multiNetwork.ChainWithAsset
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.repository.TotalIssuanceRepository
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

class ActiveEraInfo(
    val eraIndex: EraIndex,
    val exposures: AccountIdMap<Exposure>,
    val minStake: Balance,
)

class StakingSharedComputation(
    private val stakingRepository: StakingRepository,
    private val computationalCache: ComputationalCache,
    private val rewardCalculatorFactory: RewardCalculatorFactory,
    private val accountRepository: AccountRepository,
    private val bagListRepository: BagListRepository,
    private val totalIssuanceRepository: TotalIssuanceRepository,
    private val eraTimeCalculatorFactory: EraTimeCalculatorFactory,
    private val stakingConstantsRepository: StakingConstantsRepository
) {

    fun eraCalculatorFlow(stakingOption: StakingOption, scope: CoroutineScope): Flow<EraTimeCalculator> {
        val chainId = stakingOption.assetWithChain.chain.id
        val key = "ERA_TIME_CALCULATOR:$chainId"

        return computationalCache.useSharedFlow(key, scope) {
            val activeEraFlow = activeEraFlow(chainId, scope)

            eraTimeCalculatorFactory.create(stakingOption, activeEraFlow)
        }
    }

    fun activeEraFlow(chainId: ChainId, scope: CoroutineScope): Flow<EraIndex> {
        val key = "ACTIVE_ERA:$chainId"

        return computationalCache.useSharedFlow(key, scope) {
            flow {
                Log.d("PEZ_STAKE", "activeEraFlow: fetching remote activeEra for chainId=$chainId")
                val era = stakingRepository.getActiveEraIndex(chainId)
                Log.d("PEZ_STAKE", "activeEraFlow: got remote activeEra=$era")
                emit(era)

                Log.d("PEZ_STAKE", "activeEraFlow: starting local observation for chainId=$chainId")
                emitAll(stakingRepository.observeActiveEraIndex(chainId))
            }
        }
    }

    fun electedExposuresWithActiveEraFlow(chainId: ChainId, scope: CoroutineScope): Flow<ExposuresWithEraIndex> {
        val key = "ELECTED_EXPOSURES:$chainId"

        return computationalCache.useSharedFlow(key, scope) {
            activeEraFlow(chainId, scope).map { eraIndex ->
                Log.d("PEZ_STAKE", "electedExposures: fetching validators for chainId=$chainId, era=$eraIndex")
                try {
                    val exposures = stakingRepository.getElectedValidatorsExposure(chainId, eraIndex)
                    Log.d("PEZ_STAKE", "electedExposures: got ${exposures.size} validators for chainId=$chainId")
                    exposures to eraIndex
                } catch (e: Exception) {
                    Log.e("PEZ_STAKE", "electedExposures: FAILED for chainId=$chainId, era=$eraIndex", e)
                    throw e
                }
            }
        }
    }

    fun activeEraInfo(chainId: ChainId, scope: CoroutineScope): Flow<ActiveEraInfo> {
        val key = "MIN_STAKE:$chainId"

        return computationalCache.useSharedFlow(key, scope) {
            electedExposuresWithActiveEraFlow(chainId, scope).map { (exposures, activeEraIndex) ->
                Log.d("PEZ_STAKE", "activeEraInfo: calculating minStake for chainId=$chainId, era=$activeEraIndex, validators=${exposures.size}")
                try {
                    val minBond = stakingRepository.minimumNominatorBond(chainId)
                    Log.d("PEZ_STAKE", "activeEraInfo: minBond=$minBond")
                    val bagListLocator = bagListRepository.bagListLocatorOrNull(chainId)
                    val totalIssuance = totalIssuanceRepository.getTotalIssuance(chainId)
                    val bagListScoreConverter = BagListScoreConverter.U128(totalIssuance)
                    val maxElectingVoters = bagListRepository.maxElectingVotes(chainId)
                    val bagListSize = bagListRepository.bagListSize(chainId)
                    Log.d("PEZ_STAKE", "activeEraInfo: bagListSize=$bagListSize, maxElectingVoters=$maxElectingVoters")

                    val minStake = minimumStake(
                        exposures = exposures.values,
                        minimumNominatorBond = minBond,
                        bagListLocator = bagListLocator,
                        bagListScoreConverter = bagListScoreConverter,
                        bagListSize = bagListSize,
                        maxElectingVoters = maxElectingVoters
                    )
                    Log.d("PEZ_STAKE", "activeEraInfo: minStake=$minStake")

                    ActiveEraInfo(activeEraIndex, exposures, minStake)
                } catch (e: Exception) {
                    Log.e("PEZ_STAKE", "activeEraInfo: FAILED for chainId=$chainId", e)
                    throw e
                }
            }
        }
    }

    fun selectedAccountStakingStateFlow(scope: CoroutineScope, assetWithChain: ChainWithAsset): Flow<StakingState> {
        val (chain, asset) = assetWithChain
        val key = "STAKING_STATE:${assetWithChain.chain.id}:${assetWithChain.asset.id}"

        return computationalCache.useSharedFlow(key, scope) {
            accountRepository.selectedMetaAccountFlow().transformLatest { account ->
                val accountId = account.accountIdIn(chain)

                if (accountId != null) {
                    emitAll(stakingRepository.stakingStateFlow(chain, asset, accountId))
                } else {
                    emit(StakingState.NonStash(chain, asset))
                }
            }
        }
    }

    suspend fun rewardCalculator(
        stakingOption: StakingOption,
        scope: CoroutineScope
    ): RewardCalculator {
        val chainAsset = stakingOption.assetWithChain.asset

        val key = "REWARD_CALCULATOR:${chainAsset.chainId}:${chainAsset.id}:${stakingOption.additional.stakingType}"

        return computationalCache.useCache(key, scope) {
            rewardCalculatorFactory.create(stakingOption, scope)
        }
    }

    suspend fun rewardCalculator(
        chain: Chain,
        chainAsset: Chain.Asset,
        stakingType: Chain.Asset.StakingType,
        scope: CoroutineScope
    ): RewardCalculator {
        val stakingOption = createStakingOption(chain, chainAsset, stakingType)

        return rewardCalculator(stakingOption, scope)
    }
}

suspend fun StakingSharedComputation.electedExposuresInActiveEra(
    chainId: ChainId,
    scope: CoroutineScope
): AccountIdMap<Exposure> = electedExposuresInActiveEraFlow(chainId, scope).first()

fun StakingSharedComputation.electedExposuresInActiveEraFlow(chainId: ChainId, scope: CoroutineScope): Flow<AccountIdMap<Exposure>> {
    return electedExposuresWithActiveEraFlow(chainId, scope).map { (exposures, _) -> exposures }
}

suspend fun StakingSharedComputation.getActiveEra(chainId: ChainId, scope: CoroutineScope): EraIndex {
    return activeEraFlow(chainId, scope).first()
}

suspend fun StakingSharedComputation.minStake(
    chainId: ChainId,
    scope: CoroutineScope
): Balance = activeEraInfo(chainId, scope).first().minStake

suspend fun StakingSharedComputation.eraTimeCalculator(
    stakingOption: StakingOption,
    scope: CoroutineScope
) = eraCalculatorFlow(stakingOption, scope).first()
