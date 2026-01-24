package io.novafoundation.nova.runtime.ext

import io.novafoundation.nova.common.utils.TokenSymbol

val TokenSymbol.mainTokensFirstAscendingOrder
    get() = when (this.value) {
        "HEZ" -> 0
        "PEZ" -> 1
        "DOT" -> 2
        "KSM" -> 3
        "USDT" -> 4
        "USDC" -> 5
        else -> 6
    }

val TokenSymbol.alphabeticalOrder
    get() = value

fun <K> TokenSymbol.Companion.defaultComparatorFrom(extractor: (K) -> TokenSymbol): Comparator<K> = Comparator.comparing(extractor, defaultComparator())

fun TokenSymbol.Companion.defaultComparator(): Comparator<TokenSymbol> = compareBy<TokenSymbol> { it.mainTokensFirstAscendingOrder }
    .thenBy { it.alphabeticalOrder }
