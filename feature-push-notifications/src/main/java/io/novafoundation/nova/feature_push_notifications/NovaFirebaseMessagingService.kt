package io.novafoundation.nova.feature_push_notifications

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.feature_push_notifications.data.PushNotificationsService
import io.novafoundation.nova.feature_push_notifications.di.PushNotificationsFeatureApi
import io.novafoundation.nova.feature_push_notifications.di.PushNotificationsFeatureComponent
import io.novafoundation.nova.feature_push_notifications.presentation.handling.NotificationHandler
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.CoroutineContext

class NovaFirebaseMessagingService : FirebaseMessagingService(), CoroutineScope {

    override val coroutineContext: CoroutineContext = Dispatchers.Default + SupervisorJob()

    @Inject
    lateinit var pushNotificationsService: PushNotificationsService

    @Inject
    lateinit var notificationHandler: NotificationHandler

    override fun onCreate() {
        super.onCreate()

        injectDependencies()
    }

    override fun onNewToken(token: String) {
        pushNotificationsService.onTokenUpdated(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        launch {
            notificationHandler.handleNotification(message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        coroutineContext.cancel()
    }

    private fun injectDependencies() {
        FeatureUtils.getFeature<PushNotificationsFeatureComponent>(this, PushNotificationsFeatureApi::class.java)
            .inject(this)
    }

    companion object {

        // Three attempts, 1s + 2s of backoff. The caller wraps this in
        // withTimeout(SAVING_TIMEOUT = 15s), so the retries plus the attempts
        // themselves have to fit inside that budget: a fourth attempt would add
        // 4s of backoff and, if FCM answers slowly rather than failing fast,
        // could be cut off mid-retry and report a timeout instead of the real
        // error. Observed failures return in ~130ms, so this leaves ample room.
        private const val TOKEN_RETRY_ATTEMPTS = 3
        private const val TOKEN_RETRY_BASE_DELAY_MS = 1_000L

        suspend fun getToken(): String? {
            return runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
        }

        /**
         * FCM token, retrying on the transient failures Firebase expects callers to
         * handle themselves.
         *
         * getToken() surfaces SERVICE_NOT_AVAILABLE when Google's endpoint is
         * momentarily unreachable — an IOException, not a configuration problem, and
         * Firebase's own guidance is to retry with backoff. A single attempt meant a
         * few seconds of that landed in front of the user as
         * "java.util.concurrent.ExecutionException: java.io.IOException:
         * SERVICE_NOT_AVAILABLE" and enabling notifications simply failed.
         *
         * Retries three times with 1s/2s backoff. Only network failures qualify;
         * a wrong google-services.json or a missing Play Services install fails
         * differently and will not start working on the second try, so those are
         * rethrown on the first attempt.
         */
        suspend fun requestToken(): String {
            var lastError: Exception? = null

            repeat(TOKEN_RETRY_ATTEMPTS) { attempt ->
                try {
                    return FirebaseMessaging.getInstance().token.await()
                } catch (e: Exception) {
                    // The IOException arrives wrapped — the message users saw was
                    // "java.util.concurrent.ExecutionException: java.io.IOException:
                    // SERVICE_NOT_AVAILABLE" — so catching IOException alone would
                    // never match and the retry would be dead code.
                    if (!e.isTransientNetworkFailure()) throw e

                    lastError = e
                    if (attempt < TOKEN_RETRY_ATTEMPTS - 1) {
                        delay(TOKEN_RETRY_BASE_DELAY_MS shl attempt)
                    }
                }
            }

            throw lastError ?: IOException("Failed to obtain FCM token")
        }

        /**
         * True when the failure is Firebase being momentarily unreachable, which is
         * worth another attempt. A misconfigured google-services.json or a missing
         * Play Services install fails differently and will not start working on a
         * retry, so those are rethrown immediately.
         */
        private fun Throwable.isTransientNetworkFailure(): Boolean {
            var cause: Throwable? = this
            while (cause != null) {
                if (cause is IOException) return true
                cause = cause.cause
            }
            return false
        }

        suspend fun deleteToken() {
            FirebaseMessaging.getInstance().deleteToken()
        }
    }
}
