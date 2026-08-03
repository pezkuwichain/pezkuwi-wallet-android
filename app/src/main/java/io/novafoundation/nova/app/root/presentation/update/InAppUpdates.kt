package io.novafoundation.nova.app.root.presentation.update

import android.app.Activity
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Tells the user a newer version exists, and installs it without leaving the app.
 *
 * There was no such mechanism before: someone on an old build stayed on it until they
 * happened to open the Play Store. For a wallet that is worse than an inconvenience —
 * a signing fix reaches nobody until they go looking for it.
 *
 * Two flows, chosen by how far behind the user is:
 *
 *  - FLEXIBLE downloads in the background and keeps the wallet usable, then asks to
 *    restart. This is the default, because interrupting someone mid-transfer to force
 *    an update is its own kind of harm.
 *  - IMMEDIATE blocks until the update is installed. Reserved for releases marked
 *    high priority in Play Console, and for users who have ignored a flexible prompt
 *    long enough that staleness alone justifies it.
 *
 * Only works when Play installed the app. On a Firebase or sideloaded build every call
 * here resolves to "no update available" — that is the API's design, not a failure, so
 * this must never surface an error to the user.
 */
private const val LOG_TAG = "InAppUpdates"

/** Beyond this, a flexible prompt has clearly been ignored and the update is forced. */
private const val IMMEDIATE_AFTER_STALENESS_DAYS = 14

/** Play Console marks security-relevant releases at 4+; those are not optional. */
private const val IMMEDIATE_AT_PRIORITY = 4

const val REQUEST_CODE_APP_UPDATE = 4711

class InAppUpdates(private val activity: Activity) {

    private val manager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(activity) }

    /**
     * Ask Play whether a newer version exists and start the appropriate flow.
     *
     * Silent on every failure path: no store, no network, no Play install. A wallet
     * that cannot check for updates must still open.
     */
    fun checkForUpdate() {
        runCatching {
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    runCatching {
                        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@runCatching

                        val staleness = info.clientVersionStalenessDays() ?: 0
                        val forced = staleness >= IMMEDIATE_AFTER_STALENESS_DAYS ||
                            info.updatePriority() >= IMMEDIATE_AT_PRIORITY

                        val type = when {
                            forced && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                            else -> return@runCatching
                        }

                        manager.startUpdateFlowForResult(
                            info,
                            activity,
                            AppUpdateOptions.newBuilder(type).build(),
                            REQUEST_CODE_APP_UPDATE
                        )
                    }.onFailure { Log.w(LOG_TAG, "Could not start update flow", it) }
                }
                .addOnFailureListener { Log.w(LOG_TAG, "Update check failed", it) }
        }.onFailure { Log.w(LOG_TAG, "Update manager unavailable", it) }
    }

    /**
     * Finish an interrupted IMMEDIATE update, and install a FLEXIBLE one that finished
     * downloading while the app was backgrounded. Call from onResume.
     *
     * Without this an immediate update that was interrupted leaves the user on a screen
     * they cannot get past, and a completed flexible download never installs.
     */
    fun resumeIfNeeded() {
        runCatching {
            manager.appUpdateInfo.addOnSuccessListener { info ->
                runCatching {
                    when {
                        info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                            manager.startUpdateFlowForResult(
                                info,
                                activity,
                                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                                REQUEST_CODE_APP_UPDATE
                            )
                        }

                        info.installStatus() == InstallStatus.DOWNLOADED -> manager.completeUpdate()
                    }
                }.onFailure { Log.w(LOG_TAG, "Could not resume update", it) }
            }
        }.onFailure { Log.w(LOG_TAG, "Update resume unavailable", it) }
    }
}
