package io.novafoundation.nova.runtime.ext

import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain

val Chain.mainChainsFirstAscendingOrder
    get() = when (genesisHash) {
        Chain.Geneses.PEZKUWI -> 0
        Chain.Geneses.PEZKUWI_ASSET_HUB -> 1
        Chain.Geneses.PEZKUWI_PEOPLE -> 2
        Chain.Geneses.POLKADOT_ASSET_HUB -> 3
        Chain.Geneses.KUSAMA_ASSET_HUB -> 4
        else -> 5
    }

val Chain.testnetsLastAscendingOrder
    get() = if (isTestNet) {
        1
    } else {
        0
    }

val Chain.alphabeticalOrder
    get() = name

fun <K> Chain.Companion.defaultComparatorFrom(extractor: (K) -> Chain): Comparator<K> = Comparator.comparing(extractor, defaultComparator())

fun Chain.Companion.defaultComparator(): Comparator<Chain> = compareBy<Chain> { it.mainChainsFirstAscendingOrder }
    .thenBy { it.testnetsLastAscendingOrder }
    .thenBy { it.alphabeticalOrder }
