package io.novafoundation.nova.feature_assets.domain.dashboard

import io.novafoundation.nova.common.data.storage.Preferences
import java.math.BigInteger

data class MiningSimulationState(
    val eraStartedAt: Long, // 0 = era never started (no session ever activated)
    val sessionActivatedAt: Long, // 0 = no active session right now
    val baseDiamonds: Double, // frozen total within the current era
)

interface MiningSimulationRepository {

    /** Applies any pending era rollover (see [MiningSimulationFormula.ERA_DURATION_MS]) before
     *  returning the current state - callers should always go through this, never read raw prefs. */
    fun getState(accountId: Long, now: Long): MiningSimulationState

    /** Freezes the live total (as of [now]) as the new base and starts a fresh session -
     *  never resets [MiningSimulationState.baseDiamonds] to zero except via an era rollover. */
    fun activate(accountId: Long, trustScore: BigInteger, now: Long): MiningSimulationState
}

private const val MICRO_SCALE = 1_000_000L

class RealMiningSimulationRepository(
    private val preferences: Preferences
) : MiningSimulationRepository {

    override fun getState(accountId: Long, now: Long): MiningSimulationState {
        return normalize(accountId, now)
    }

    override fun activate(accountId: Long, trustScore: BigInteger, now: Long): MiningSimulationState {
        val normalized = normalize(accountId, now)

        val liveDiamonds = MiningSimulationFormula.diamondsWithinEra(
            baseDiamonds = normalized.baseDiamonds,
            sessionActivatedAt = normalized.sessionActivatedAt,
            trustScore = trustScore,
            now = now
        )

        val eraStartedAt = if (normalized.eraStartedAt == 0L) now else normalized.eraStartedAt

        val newState = normalized.copy(
            eraStartedAt = eraStartedAt,
            sessionActivatedAt = now,
            baseDiamonds = liveDiamonds
        )
        persist(accountId, newState)
        return newState
    }

    /** Detects and applies an era rollover (>= ERA_DURATION_MS since era start) - resets both the
     *  diamond total and the active session, since that era's simulated pool is "distributed". */
    private fun normalize(accountId: Long, now: Long): MiningSimulationState {
        val stored = read(accountId)
        if (stored.eraStartedAt == 0L) return stored

        val erasElapsed = (now - stored.eraStartedAt) / MiningSimulationFormula.ERA_DURATION_MS
        if (erasElapsed <= 0L) return stored

        val rolledOver = stored.copy(
            eraStartedAt = stored.eraStartedAt + erasElapsed * MiningSimulationFormula.ERA_DURATION_MS,
            sessionActivatedAt = 0L,
            baseDiamonds = 0.0
        )
        persist(accountId, rolledOver)
        return rolledOver
    }

    private fun read(accountId: Long): MiningSimulationState {
        return MiningSimulationState(
            eraStartedAt = preferences.getLong(eraKey(accountId), 0L),
            sessionActivatedAt = preferences.getLong(sessionKey(accountId), 0L),
            baseDiamonds = preferences.getLong(diamondsKey(accountId), 0L) / MICRO_SCALE.toDouble()
        )
    }

    private fun persist(accountId: Long, state: MiningSimulationState) {
        val editor = preferences.edit()
        editor.putLong(eraKey(accountId), state.eraStartedAt)
        editor.putLong(sessionKey(accountId), state.sessionActivatedAt)
        editor.putLong(diamondsKey(accountId), (state.baseDiamonds * MICRO_SCALE).toLong())
        editor.apply()
    }

    private fun eraKey(accountId: Long) = "MiningSimulationRepository.era.$accountId"
    private fun sessionKey(accountId: Long) = "MiningSimulationRepository.session.$accountId"
    private fun diamondsKey(accountId: Long) = "MiningSimulationRepository.diamonds.$accountId"
}
