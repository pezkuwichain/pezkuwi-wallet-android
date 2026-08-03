package io.novafoundation.nova.app.root.presentation.update

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import io.novafoundation.nova.common.appstore.AppReviewTracker

/**
 * Shows Play's native rating card, in-app, when the tracker says the moment is right.
 *
 * Play decides the rest: whether the user has already rated, and whether their quota
 * allows another showing. Neither is visible to us, and neither is reported back — the
 * flow reports only that it finished, not what the user did. So there is nothing to
 * branch on afterwards, and nothing to record beyond "we spent an attempt".
 *
 * Failure is silent by design. A rating prompt that surfaces an error is worse than no
 * prompt at all.
 */
private const val LOG_TAG = "AppReviewPrompt"

class AppReviewPrompt(
    private val activity: Activity,
    private val tracker: AppReviewTracker,
) {

    fun requestIfEarned() {
        if (!tracker.shouldRequestReview()) return

        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow()
                .addOnSuccessListener { info ->
                    runCatching {
                        manager.launchReviewFlow(activity, info)
                            .addOnCompleteListener {
                                // Completion says the flow ended, not that a review was
                                // left. Record either way: the attempt is what Play counts.
                                tracker.onReviewRequested()
                            }
                    }.onFailure { Log.w(LOG_TAG, "Could not launch review flow", it) }
                }
                .addOnFailureListener { Log.w(LOG_TAG, "Review flow unavailable", it) }
        }.onFailure { Log.w(LOG_TAG, "Review manager unavailable", it) }
    }
}
