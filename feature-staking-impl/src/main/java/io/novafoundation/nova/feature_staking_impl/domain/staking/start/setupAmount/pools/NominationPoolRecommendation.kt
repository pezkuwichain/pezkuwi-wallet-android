package io.novafoundation.nova.feature_staking_impl.domain.staking.start.setupAmount.pools

import io.novafoundation.nova.feature_staking_impl.data.StakingOption
import io.novafoundation.nova.feature_staking_impl.domain.nominationPools.pools.recommendation.NominationPoolRecommenderFactory
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.selection.StartMultiStakingSelection
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.setupAmount.SingleStakingRecommendation
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

class NominationPoolRecommendation(
    private val scope: CoroutineScope,
    private val stakingOption: StakingOption,
    private val nominationPoolRecommenderFactory: NominationPoolRecommenderFactory
) : SingleStakingRecommendation {

    private val recommendator = scope.async {
        Log.d("PEZ_STAKE", "NomPoolRecommendation: creating recommender...")
        try {
            val result = nominationPoolRecommenderFactory.create(stakingOption, scope)
            Log.d("PEZ_STAKE", "NomPoolRecommendation: recommender created successfully")
            result
        } catch (e: Exception) {
            Log.e("PEZ_STAKE", "NomPoolRecommendation: recommender creation FAILED", e)
            throw e
        }
    }

    override suspend fun recommendedSelection(stake: Balance): StartMultiStakingSelection? {
        Log.d("PEZ_STAKE", "NomPoolRecommendation: awaiting recommender...")
        val recommendedPool = recommendator.await().recommendedPool() ?: run {
            Log.d("PEZ_STAKE", "NomPoolRecommendation: no recommended pool found")
            return null
        }
        Log.d("PEZ_STAKE", "NomPoolRecommendation: recommended pool=${recommendedPool.id}")

        return NominationPoolSelection(recommendedPool, stakingOption, stake)
    }
}
