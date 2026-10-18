package io.novafoundation.nova.splash.presentation

import io.novafoundation.nova.common.utils.splash.SplashPassedObserver
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.splash.SplashRouter
import io.novafoundation.nova.test_shared.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    private val router = mock(SplashRouter::class.java)
    private val repository = mock(AccountRepository::class.java)
    private val splashPassedObserver = mock(SplashPassedObserver::class.java)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The splash screen asks again on every onResume, including the one after the
    // activity is recreated; navigating twice crashed the app on the welcome screen.
    @Test
    fun `navigates to the welcome screen once however often it is asked`() {
        runBlocking { whenever(repository.isAccountSelected()).thenReturn(false) }
        val viewModel = SplashViewModel(router, repository, splashPassedObserver)

        viewModel.openInitialDestination()
        viewModel.openInitialDestination()

        verify(router, times(1)).openWelcomeScreen()
    }
}
