package io.novafoundation.nova.splash.presentation

import androidx.lifecycle.viewModelScope
import io.novafoundation.nova.common.base.BaseViewModel
import io.novafoundation.nova.common.utils.splash.SplashPassedObserver
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.splash.SplashRouter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SplashViewModel(
    private val router: SplashRouter,
    private val repository: AccountRepository,
    private val splashPassedObserver: SplashPassedObserver
) : BaseViewModel() {

    private var initialDestinationJob: Job? = null

    // Called from the splash screen's onResume, which runs again when the activity is
    // recreated (rotation, theme, locale) while this view model survives. Starting a
    // second navigation then finds the first one already on the welcome screen, which
    // has no route to welcome, and crashes the app. The first call decides.
    fun openInitialDestination() {
        if (initialDestinationJob != null) return

        initialDestinationJob = viewModelScope.launch {
            if (repository.isAccountSelected()) {
                openPinCode()
            } else {
                openWelcomeScreen()
            }

            splashPassedObserver.setSplashPassed()
        }
    }

    private suspend fun openPinCode() {
        if (repository.isCodeSet()) {
            router.openInitialCheckPincode()
        } else {
            router.openCreatePincode()
        }
    }

    private fun openWelcomeScreen() {
        router.openWelcomeScreen()
    }
}
