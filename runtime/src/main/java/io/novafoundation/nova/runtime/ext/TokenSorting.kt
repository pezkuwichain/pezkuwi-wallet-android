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
        "TRX" -> 6
        "SOL" -> 7
        "BTC" -> 8
        "ETH" -> 9
        "BNB" -> 10
        "AVAX" -> 11
        "LINK" -> 12
        "TAO" -> 13
        else -> 14
    }

val TokenSymbol.alphabeticalOrder
    get() = value

fun <K> TokenSymbol.Companion.defaultComparatorFrom(extractor: (K) -> TokenSymbol): Comparator<K> = Comparator.comparing(extractor, defaultComparator())

fun TokenSymbol.Companion.defaultComparator(): Comparator<TokenSymbol> = compareBy<TokenSymbol> { it.mainTokensFirstAscendingOrder }
    .thenBy { it.alphabeticalOrder }
