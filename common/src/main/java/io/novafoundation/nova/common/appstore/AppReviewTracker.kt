package io.novafoundation.nova.common.appstore

import io.novafoundation.nova.common.data.storage.Preferences
import java.util.concurrent.TimeUnit

/**
 * Decides when to ask for a Play Store review.
 *
 * Play gives no way to ask whether someone has already rated, and it throttles the
 * prompt to a handful of showings per user per year — a call made too often is
 * silently dropped. So the gates below are not there to avoid nagging, which Play
 * already handles; they are there to spend the few real chances on a good moment.
 *
 * Play policy forbids pre-qualifying ("do you like the app?"), rewarding a review, or
 * asking for a *positive* one. The prompt has to be unconditional, so the only thing
 * left to choose is when.
 *
 * Nothing here may throw. It is called from the extrinsic submission path, and a
 * rating counter must never be able to fail a transfer.
 */
interface AppReviewTracker {

    /** Called after an on-chain operation or a wallet creation succeeds. */
    fun onMeaningfulSuccess()

    /** Called when the user hits an error, which parks the prompt for a while. */
    fun onFailure()

    /** True when every gate is satisfied. Ask Play only then. */
    fun shouldRequestReview(): Boolean

    /** Called once the Play flow has been launched, whatever its outcome. */
    fun onReviewRequested()
}

private const val KEY_SUCCESSES = "app_review_success_count"
private const val KEY_FIRST_SEEN = "app_review_first_seen_at"
private const val KEY_LAST_ASKED = "app_review_last_asked_at"
private const val KEY_LAST_FAILURE = "app_review_last_failure_at"

/** Someone with one transfer behind them has no opinion yet. */
private const val MIN_SUCCESSES = 3

/** A first-day user is judging the download, not the wallet. */
private val MIN_AGE_MS = TimeUnit.DAYS.toMillis(3)

/** Play's own quota is roughly this scale; asking more often just wastes attempts. */
private val MIN_INTERVAL_MS = TimeUnit.DAYS.toMillis(90)

/** Long enough that a fresh failure is no longer what the user has in mind. */
private val FAILURE_COOLDOWN_MS = TimeUnit.DAYS.toMillis(2)

class RealAppReviewTracker(
    private val preferences: Preferences,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AppReviewTracker {

    override fun onMeaningfulSuccess() = safely {
        val now = currentTimeMillis()
        if (preferences.getLong(KEY_FIRST_SEEN, 0L) == 0L) {
            preferences.putLong(KEY_FIRST_SEEN, now)
        }
        preferences.putInt(KEY_SUCCESSES, preferences.getInt(KEY_SUCCESSES, 0) + 1)
    }

    override fun onFailure() = safely {
        preferences.putLong(KEY_LAST_FAILURE, currentTimeMillis())
    }

    override fun shouldRequestReview(): Boolean {
        return runCatching {
            val now = currentTimeMillis()

            val successes = preferences.getInt(KEY_SUCCESSES, 0)
            if (successes < MIN_SUCCESSES) return@runCatching false

            val firstSeen = preferences.getLong(KEY_FIRST_SEEN, 0L)
            if (firstSeen == 0L || now - firstSeen < MIN_AGE_MS) return@runCatching false

            val lastAsked = preferences.getLong(KEY_LAST_ASKED, 0L)
            if (lastAsked != 0L && now - lastAsked < MIN_INTERVAL_MS) return@runCatching false

            val lastFailure = preferences.getLong(KEY_LAST_FAILURE, 0L)
            if (lastFailure != 0L && now - lastFailure < FAILURE_COOLDOWN_MS) return@runCatching false

            true
        }.getOrDefault(false)
    }

    override fun onReviewRequested() = safely {
        // Written whether or not the user acted on the card: Play does not report the
        // outcome, and an attempt spends quota either way.
        preferences.putLong(KEY_LAST_ASKED, currentTimeMillis())
    }

    private inline fun safely(block: () -> Unit) {
        runCatching(block)
    }
}
