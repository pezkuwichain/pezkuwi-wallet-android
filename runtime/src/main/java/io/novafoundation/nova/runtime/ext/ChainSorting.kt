package io.novafoundation.nova.runtime.ext

import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain

val Chain.mainChainsFirstAscendingOrder
    get() = when {
        // Pezkuwi ecosystem first
        genesisHash == Chain.Geneses.PEZKUWI -> 0
        genesisHash == Chain.Geneses.PEZKUWI_ASSET_HUB -> 1
        genesisHash == Chain.Geneses.PEZKUWI_PEOPLE -> 2
        // Then Polkadot ecosystem
        genesisHash == Chain.Geneses.POLKADOT -> 3
        genesisHash == Chain.Geneses.POLKADOT_ASSET_HUB -> 4
        // Then Kusama ecosystem
        genesisHash == Chain.Geneses.KUSAMA -> 5
        genesisHash == Chain.Geneses.KUSAMA_ASSET_HUB -> 6
        // Then Ethereum, then Tron - not identified by genesisHash (that's substrate-only), so this can't
        // stay a `when (genesisHash)` subject match once these two are added
        id == Chain.Ids.ETHEREUM -> 7
        id == Chain.Ids.TRON -> 8
        // Everything else
        else -> 9
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
