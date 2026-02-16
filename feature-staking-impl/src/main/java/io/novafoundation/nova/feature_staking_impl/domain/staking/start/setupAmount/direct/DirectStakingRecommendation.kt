package io.novafoundation.nova.feature_staking_impl.domain.staking.start.setupAmount.direct

import io.novafoundation.nova.feature_staking_impl.data.StakingOption
import io.novafoundation.nova.feature_staking_impl.data.chain
import io.novafoundation.nova.feature_staking_impl.data.repository.StakingConstantsRepository
import io.novafoundation.nova.feature_staking_impl.domain.recommendations.ValidatorRecommenderFactory
import io.novafoundation.nova.feature_staking_impl.domain.recommendations.settings.RecommendationSettingsProviderFactory
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.selection.StartMultiStakingSelection
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.setupAmount.SingleStakingRecommendation
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

class DirectStakingRecommendation(
    private val validatorRecommenderFactory: ValidatorRecommenderFactory,
    private val recommendationSettingsProviderFactory: RecommendationSettingsProviderFactory,
    private val stakingConstantsRepository: StakingConstantsRepository,
    private val stakingOption: StakingOption,
    private val scope: CoroutineScope
) : SingleStakingRecommendation {

    private val recommendator = scope.async {
        Log.d("PEZ_STAKE", "DirectRecommendation: creating validator recommender...")
        try {
            val result = validatorRecommenderFactory.create(scope)
            Log.d("PEZ_STAKE", "DirectRecommendation: validator recommender created")
            result
        } catch (e: Exception) {
            Log.e("PEZ_STAKE", "DirectRecommendation: validator recommender FAILED", e)
            throw e
        }
    }

    private val recommendationSettingsProvider = scope.async {
        Log.d("PEZ_STAKE", "DirectRecommendation: creating settings provider...")
        try {
            val result = recommendationSettingsProviderFactory.create(scope)
            Log.d("PEZ_STAKE", "DirectRecommendation: settings provider created")
            result
        } catch (e: Exception) {
            Log.e("PEZ_STAKE", "DirectRecommendation: settings provider FAILED", e)
            throw e
        }
    }

    override suspend fun recommendedSelection(stake: Balance): StartMultiStakingSelection {
        Log.d("PEZ_STAKE", "DirectRecommendation: awaiting settings provider...")
        val provider = recommendationSettingsProvider.await()
        Log.d("PEZ_STAKE", "DirectRecommendation: got settings provider")
        val stakingChainId = stakingOption.chain.parentId ?: stakingOption.chain.id
        val maximumValidatorsPerNominator = stakingConstantsRepository.maxValidatorsPerNominator(stakingChainId, stake)
        val recommendationSettings = provider.recommendedSettings(maximumValidatorsPerNominator)
        Log.d("PEZ_STAKE", "DirectRecommendation: awaiting recommender...")
        val recommendator = recommendator.await()
        Log.d("PEZ_STAKE", "DirectRecommendation: got recommender, getting recommendations...")

        val recommendedValidators = recommendator.recommendations(recommendationSettings)
        Log.d("PEZ_STAKE", "DirectRecommendation: got ${recommendedValidators.size} recommended validators")

        return DirectStakingSelection(
            validators = recommendedValidators,
            validatorsLimit = maximumValidatorsPerNominator,
            stakingOption = stakingOption,
            stake = stake
        )
    }
}
