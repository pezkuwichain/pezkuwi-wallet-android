package io.novafoundation.nova.feature_staking_impl.domain.staking.start.setupAmount.selectionType

import io.novafoundation.nova.common.validation.ValidationSystem
import io.novafoundation.nova.common.validation.copyIntoCurrent
import io.novafoundation.nova.feature_staking_impl.data.stakingType
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.selection.RecommendableMultiStakingSelection
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.selection.SelectionTypeSource
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.selection.StartMultiStakingSelection
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.selection.store.StartMultiStakingSelectionStore
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.validations.StartMultiStakingValidationSystem
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.common.validations.availableBalanceGapValidation
import io.novafoundation.nova.feature_staking_impl.domain.staking.start.setupAmount.SingleStakingProperties
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.feature_wallet_api.data.repository.BalanceLocksRepository
import io.novafoundation.nova.feature_wallet_api.domain.model.Asset
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

class AutomaticMultiStakingSelectionType(
    private val candidates: List<SingleStakingProperties>,
    private val selectionStore: StartMultiStakingSelectionStore,
    private val locksRepository: BalanceLocksRepository,
) : MultiStakingSelectionType {

    override suspend fun validationSystem(selection: StartMultiStakingSelection): StartMultiStakingValidationSystem {
        val candidateValidationSystem = candidates.first { it.stakingType == selection.stakingOption.stakingType }
            .validationSystem

        return ValidationSystem {
            // should always go before `candidateValidationSystem` since it delegates some cases to type-specific validations
            availableBalanceGapValidation(
                candidates = candidates,
                locksRepository = locksRepository
            )

            candidateValidationSystem.copyIntoCurrent()
        }
    }

    override suspend fun availableBalance(asset: Asset): Balance {
        return candidates.maxOf { it.availableBalance(asset) }
    }

    override suspend fun maxAmountToStake(asset: Asset): Balance {
        return candidates.maxOf { it.maximumToStake(asset) }
    }

    override suspend fun updateSelectionFor(stake: Balance) {
        Log.d("PEZ_STAKE", "updateSelectionFor: stake=$stake")
        val stakingProperties = typePropertiesFor(stake)
        Log.d("PEZ_STAKE", "updateSelectionFor: got properties type=${stakingProperties.stakingType}")
        val candidates = stakingProperties.recommendation.recommendedSelection(stake) ?: run {
            Log.d("PEZ_STAKE", "updateSelectionFor: recommendedSelection returned null, returning")
            return
        }
        Log.d("PEZ_STAKE", "updateSelectionFor: got recommended selection")

        val recommendableSelection = RecommendableMultiStakingSelection(
            source = SelectionTypeSource.Automatic,
            selection = candidates,
            properties = stakingProperties
        )

        selectionStore.updateSelection(recommendableSelection)
        Log.d("PEZ_STAKE", "updateSelectionFor: selection updated successfully")
    }

    private suspend fun typePropertiesFor(stake: Balance): SingleStakingProperties {
        Log.d("PEZ_STAKE", "typePropertiesFor: trying ${candidates.size} candidates")
        for ((index, candidate) in candidates.withIndex()) {
            Log.d("PEZ_STAKE", "typePropertiesFor: checking candidate $index type=${candidate.stakingType}")
            try {
                val minStake = candidate.minStake()
                Log.d("PEZ_STAKE", "typePropertiesFor: candidate $index minStake=$minStake, stake=$stake, allows=${minStake <= stake}")
                if (minStake <= stake) return candidate
            } catch (e: CancellationException) {
                Log.d("PEZ_STAKE", "typePropertiesFor: candidate $index cancelled, rethrowing")
                throw e
            } catch (e: Exception) {
                Log.e("PEZ_STAKE", "typePropertiesFor: candidate $index minStake() threw", e)
            }
        }
        Log.d("PEZ_STAKE", "typePropertiesFor: no candidate allows, finding minimum")
        return candidates.findWithMinimumStake()
    }

    private suspend fun List<SingleStakingProperties>.firstAllowingToStake(stake: Balance): SingleStakingProperties? {
        return find { it.minStake() <= stake }
    }

    private suspend fun List<SingleStakingProperties>.findWithMinimumStake(): SingleStakingProperties {
        return minBy { it.minStake() }
    }
}
