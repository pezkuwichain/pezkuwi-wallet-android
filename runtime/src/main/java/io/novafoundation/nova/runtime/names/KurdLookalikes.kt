package io.novafoundation.nova.runtime.names

/**
 * Official names a typed one could be mistaken for. Zone names are institutional by construction
 * (`*.gov.kurd`, `*.edu.kurd`, …), and the commonest imitation flattens the zone into the label:
 * `serok-gov.kurd` or `serokgov.kurd` for `serok.gov.kurd`. Candidates are only guesses: a warning
 * is shown once one of them resolves, proven, as an official name.
 */
object KurdLookalikes {

    /** Zones created at genesis (decisions D6, D13). */
    val ZONES = listOf("gov", "edu", "mil", "int", "org", "net")

    fun candidates(name: KurdName): List<KurdName> {
        val top = name.top
        if (top in ZONES) return emptyList()

        return ZONES.mapNotNull { zone ->
            val prefix = when {
                top.endsWith("-$zone") -> top.removeSuffix("-$zone")
                top.endsWith(zone) -> top.removeSuffix(zone)
                else -> return@mapNotNull null
            }
            if (!KurdName.isValidLabel(prefix)) return@mapNotNull null

            KurdName.parse((name.labels.dropLast(1) + prefix + zone).joinToString(".") + KurdName.SUFFIX)
        }.distinct()
    }
}
