package io.novafoundation.nova.feature_assets.domain.dashboard

import java.math.BigInteger

/**
 * Cosmetic, client-side-only preview of what a user's PEZ trust-score-weighted emission share
 * could look like - not tied to any real distribution mechanism or backend. Numbers loosely track
 * the real era emission (92.5M PEZ per ~30-day era) without claiming accuracy; see the "referral
 * incentive" mining card this backs.
 */
object MiningSimulationFormula {

    private const val ERA_DAYS = 30
    const val ERA_DURATION_MS = ERA_DAYS * 24 * 60 * 60 * 1000L
    private const val ERA_MINUTES = ERA_DAYS * 24 * 60
    private const val ERA_POOL_PEZ = 92_500_000.0

    /** Assumed total trust score across the network - unknowable client-side, so this is a
     *  simulation-only reference point, not a real aggregate. */
    private const val REFERENCE_TOTAL_TRUST = 500_000.0

    const val SESSION_DURATION_MS = 24 * 60 * 60 * 1000L

    private val poolPerMinute = ERA_POOL_PEZ / ERA_MINUTES

    /** Zero trust score always yields zero rate - trust score is a direct multiplier, not an offset. */
    fun ratePerMinute(trustScore: BigInteger): Double {
        if (trustScore <= BigInteger.ZERO) return 0.0

        return poolPerMinute * (trustScore.toDouble() / REFERENCE_TOTAL_TRUST)
    }

    fun isSessionActive(sessionActivatedAt: Long, now: Long): Boolean {
        return sessionActivatedAt > 0L && now < sessionActivatedAt + SESSION_DURATION_MS
    }

    /** [baseDiamonds] is the frozen within-era total as of [sessionActivatedAt] (0 = no active
     *  session right now, in which case this is just the frozen total with nothing accruing). */
    fun diamondsWithinEra(baseDiamonds: Double, sessionActivatedAt: Long, trustScore: BigInteger, now: Long): Double {
        if (sessionActivatedAt <= 0L) return baseDiamonds

        val sessionEnd = sessionActivatedAt + SESSION_DURATION_MS
        val effectiveNow = minOf(now, sessionEnd)
        val elapsedMinutes = (effectiveNow - sessionActivatedAt).coerceAtLeast(0) / 60_000.0

        return baseDiamonds + elapsedMinutes * ratePerMinute(trustScore)
    }
}
