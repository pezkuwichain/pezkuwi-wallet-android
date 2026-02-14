package io.novafoundation.nova.feature_staking_impl.domain.rewards

import android.util.Log
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.feature_account_api.data.model.AccountIdMap
import io.novafoundation.nova.feature_staking_api.domain.api.StakingRepository
import io.novafoundation.nova.feature_staking_api.domain.model.Exposure
import io.novafoundation.nova.feature_staking_api.domain.model.ValidatorPrefs
import io.novafoundation.nova.feature_staking_impl.data.StakingOption
import io.novafoundation.nova.feature_staking_impl.data.chain
import io.novafoundation.nova.feature_staking_impl.data.repository.ParasRepository
import io.novafoundation.nova.feature_staking_impl.data.repository.VaraRepository
import io.novafoundation.nova.feature_staking_impl.data.stakingType
import io.novafoundation.nova.feature_staking_impl.data.unwrapNominationPools
import io.novafoundation.nova.feature_staking_impl.domain.common.StakingSharedComputation
import io.novafoundation.nova.feature_staking_impl.domain.common.eraTimeCalculator
import io.novafoundation.nova.feature_staking_impl.domain.error.accountIdNotFound
import io.novafoundation.nova.runtime.ext.Geneses
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.ALEPH_ZERO
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.MYTHOS
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.NOMINATION_POOLS
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.PARACHAIN
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.RELAYCHAIN
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.RELAYCHAIN_AURA
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.TURING
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain.Asset.StakingType.UNSUPPORTED
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.repository.TotalIssuanceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger

class RewardCalculatorFactory(
    private val stakingRepository: StakingRepository,
    private val totalIssuanceRepository: TotalIssuanceRepository,
    private val shareStakingSharedComputation: dagger.Lazy<StakingSharedComputation>,
    private val parasRepository: ParasRepository,
    private val varaRepository: VaraRepository,
) {

    suspend fun create(
        stakingOption: StakingOption,
        exposures: AccountIdMap<Exposure>,
        validatorsPrefs: AccountIdMap<ValidatorPrefs?>,
        scope: CoroutineScope
    ): RewardCalculator = withContext(Dispatchers.Default) {
        // For parachains (e.g. Asset Hub), staking lives on the parent relay chain.
        // TotalIssuance must come from there, not from the parachain.
        val stakingChainId = stakingOption.assetWithChain.chain.parentId ?: stakingOption.assetWithChain.chain.id
        val totalIssuance = totalIssuanceRepository.getTotalIssuance(stakingChainId)

        Log.d("PEZ_STAKING", "create(4-param) exposures=${exposures.size} validatorsPrefs=${validatorsPrefs.size}")
        Log.d("PEZ_STAKING", "exposureKeys=${exposures.keys.take(3).map { it.take(16) }}")
        Log.d("PEZ_STAKING", "prefKeys=${validatorsPrefs.keys.take(3).map { it.take(16) }}")

        val validators = exposures.keys.mapNotNull { accountIdHex ->
            val exposure = exposures[accountIdHex] ?: accountIdNotFound(accountIdHex)
            val validatorPrefs = validatorsPrefs[accountIdHex] ?: return@mapNotNull null

            RewardCalculationTarget(
                accountIdHex = accountIdHex,
                totalStake = exposure.total,
                commission = validatorPrefs.commission
            )
        }

        Log.d("PEZ_STAKING", "totalIssuance=$totalIssuance validators=${validators.size} stakingChainId=${stakingChainId.take(12)}")

        stakingOption.createRewardCalculator(validators, totalIssuance, stakingChainId, scope)
    }

    suspend fun create(stakingOption: StakingOption, scope: CoroutineScope): RewardCalculator = withContext(Dispatchers.Default) {
        val chain = stakingOption.assetWithChain.chain
        val chainId = chain.id
        // For parachains with a parent relay chain, staking exposures live on the relay chain
        val exposureChainId = chain.parentId ?: chainId

        Log.d(
            "PEZ_STAKING",
            "RewardCalculatorFactory.create() chainId=${chainId.take(12)}" +
                " exposureChainId=${exposureChainId.take(12)}" +
                " stakingType=${stakingOption.additional.stakingType}"
        )

        val activeEra = stakingRepository.getActiveEraIndex(exposureChainId)
        Log.d("PEZ_STAKING", "ActiveEra: $activeEra for ${exposureChainId.take(12)}")

        val exposures = stakingRepository.getElectedValidatorsExposure(exposureChainId, activeEra)
        Log.d("PEZ_STAKING", "Exposures: ${exposures.size}")

        val validatorsPrefs = stakingRepository.getValidatorPrefs(exposureChainId, exposures.keys)
        Log.d("PEZ_STAKING", "ValidatorPrefs: ${validatorsPrefs.size}")

        create(stakingOption, exposures, validatorsPrefs, scope)
    }

    private suspend fun StakingOption.createRewardCalculator(
        validators: List<RewardCalculationTarget>,
        totalIssuance: BigInteger,
        stakingChainId: ChainId,
        scope: CoroutineScope
    ): RewardCalculator {
        return when (unwrapNominationPools().stakingType) {
            RELAYCHAIN, RELAYCHAIN_AURA -> {
                val custom = customRelayChainCalculator(validators, totalIssuance, scope)
                if (custom != null) return custom

                // Query parachains from the relay chain, not from Asset Hub
                val activePublicParachains = parasRepository.activePublicParachains(stakingChainId)
                val inflationConfig = InflationConfig.create(stakingChainId, activePublicParachains)

                RewardCurveInflationRewardCalculator(validators, totalIssuance, inflationConfig)
            }

            ALEPH_ZERO -> AlephZeroRewardCalculator(validators, chainAsset = assetWithChain.asset)
            NOMINATION_POOLS, UNSUPPORTED, PARACHAIN, TURING, MYTHOS -> throw IllegalStateException("Unknown staking type in RelaychainRewardFactory")
        }
    }

    private suspend fun StakingOption.customRelayChainCalculator(
        validators: List<RewardCalculationTarget>,
        totalIssuance: BigInteger,
        scope: CoroutineScope
    ): RewardCalculator? {
        return when (chain.id) {
            Chain.Geneses.VARA -> Vara(chain.id, validators, totalIssuance)
            Chain.Geneses.POLKADOT -> PolkadotInflationPrediction(validators, totalIssuance, scope)
            else -> null
        }
    }

    private fun InflationConfig.Companion.create(chainId: ChainId, activePublicParachains: Int?): InflationConfig {
        return when (chainId) {
            Chain.Geneses.POLKADOT -> Polkadot(activePublicParachains)
            Chain.Geneses.AVAIL_TURING_TESTNET, Chain.Geneses.AVAIL -> Avail()
            else -> Default(activePublicParachains)
        }
    }

    private suspend fun Vara(
        chainId: ChainId,
        validators: List<RewardCalculationTarget>,
        totalIssuance: BigInteger
    ): RewardCalculator? {
        return runCatching {
            val inflationInfo = varaRepository.getVaraInflation(chainId)

            VaraRewardCalculator(validators, totalIssuance, inflationInfo)
        }
            .onFailure {
                Log.e(LOG_TAG, "Failed to create Vara reward calculator, fallbacking to default", it)
            }
            .getOrNull()
    }

    private suspend fun StakingOption.PolkadotInflationPrediction(
        validators: List<RewardCalculationTarget>,
        totalIssuance: BigInteger,
        scope: CoroutineScope
    ): RewardCalculator? {
        return runCatching {
            val eraRewardCalculator = shareStakingSharedComputation.get().eraTimeCalculator(this, scope)
            val eraDuration = eraRewardCalculator.eraDuration()

            val inflationPredictionInfo = stakingRepository.getInflationPredictionInfo(chain.id)

            InflationPredictionInfoCalculator(
                inflationPredictionInfo = inflationPredictionInfo,
                eraDuration = eraDuration,
                totalIssuance = totalIssuance,
                validators = validators
            )
        }
            .onFailure {
                Log.e("RewardCalculatorFactory", "Failed to create Polkadot Inflation Prediction reward calculator, fallbacking to default", it)
            }
            .getOrNull()
    }
}
