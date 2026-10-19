package io.novafoundation.nova.feature_deep_linking.presentation.handling

import android.net.Uri
import io.novafoundation.nova.feature_deep_linking.presentation.handling.common.DeepLinkHandlingException.ReferendumHandlingException
import io.novafoundation.nova.test_shared.whenever
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class RootDeepLinkHandlerTest {

    private val link = mock(Uri::class.java)
    private val pendingDeepLinkProvider = mock(PendingDeepLinkProvider::class.java)

    private class FailingHandler(private val error: Throwable) : DeepLinkHandler {
        override val callbackFlow: Flow<CallbackEvent> = emptyFlow()
        override suspend fun matches(data: Uri) = true
        override suspend fun handleDeepLink(data: Uri): Result<Unit> = Result.failure(error)
    }

    private fun rootWith(error: Throwable) = RootDeepLinkHandler(pendingDeepLinkProvider, listOf(FailingHandler(error)))

    // A link that reaches a handler and fails for good was kept as pending and replayed on every
    // launch, showing "Referendum not found" each time the app opened.
    @Test
    fun `a fresh link that fails for good is not kept as pending`() = runBlocking {
        rootWith(ReferendumHandlingException.ReferendumIsNotSpecified).handleDeepLink(link)

        verify(pendingDeepLinkProvider).clear()
    }

    @Test
    fun `a pending link that fails for good is dropped`() = runBlocking {
        whenever(pendingDeepLinkProvider.get()).thenReturn(link)

        rootWith(ReferendumHandlingException.ReferendumIsNotSpecified).checkAndHandlePendingDeepLink()

        verify(pendingDeepLinkProvider).clear()
    }

    @Test
    fun `a link that fails for another reason stays pending for a retry`() = runBlocking {
        rootWith(IllegalStateException("not ready yet")).handleDeepLink(link)

        verify(pendingDeepLinkProvider, never()).clear()
    }
}
