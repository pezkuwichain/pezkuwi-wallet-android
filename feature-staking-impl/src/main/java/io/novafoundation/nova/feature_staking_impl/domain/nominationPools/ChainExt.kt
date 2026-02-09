package io.novafoundation.nova.feature_staking_impl.domain.nominationPools

import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain

/**
 * Finds the staking type that backs nomination pools.
 *
 * In Polkadot 1.0: Both relaychain and nomination-pools exist on the same chain
 * In Polkadot 2.0: nomination-pools may exist on Asset Hub without local backing type
 *                  (backing is via XCM from relay chain)
 *
 * @return The backing staking type, or RELAYCHAIN as default for Polkadot 2.0 compatibility
 */
fun Chain.Asset.findStakingTypeBackingNominationPools(): Chain.Asset.StakingType {
    return staking.firstOrNull { it != Chain.Asset.StakingType.NOMINATION_POOLS }
        ?: Chain.Asset.StakingType.RELAYCHAIN  // Polkadot 2.0: default to RELAYCHAIN for Asset Hub
}

/**
 * Checks if this asset has nomination pools backed by a local staking type.
 * Returns false for Polkadot 2.0 Asset Hub where backing is cross-chain.
 */
fun Chain.Asset.hasLocalNominationPoolsBacking(): Boolean {
    return staking.any { it != Chain.Asset.StakingType.NOMINATION_POOLS }
}
