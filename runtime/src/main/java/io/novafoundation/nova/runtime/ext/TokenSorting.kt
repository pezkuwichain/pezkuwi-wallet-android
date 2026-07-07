package io.novafoundation.nova.runtime.ext

import io.novafoundation.nova.common.utils.TokenSymbol

val TokenSymbol.mainTokensFirstAscendingOrder
    get() = when (this.value) {
        "HEZ" -> 0
        "PEZ" -> 1
        "USDT" -> 2
        "DOT" -> 3
        "KSM" -> 4
        "USDC" -> 5
        "BTC" -> 6
        "ETH" -> 7
        "BNB" -> 8
        "AVAX" -> 9
        "LINK" -> 10
        "UNI" -> 11
        "TAO" -> 12
        else -> 13
    }

val TokenSymbol.alphabeticalOrder
    get() = value

fun <K> TokenSymbol.Companion.defaultComparatorFrom(extractor: (K) -> TokenSymbol): Comparator<K> = Comparator.comparing(extractor, defaultComparator())

fun TokenSymbol.Companion.defaultComparator(): Comparator<TokenSymbol> = compareBy<TokenSymbol> { it.mainTokensFirstAscendingOrder }
    .thenBy { it.alphabeticalOrder }
