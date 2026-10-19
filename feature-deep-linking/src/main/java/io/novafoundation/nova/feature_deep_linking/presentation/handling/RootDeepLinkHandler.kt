package io.novafoundation.nova.feature_deep_linking.presentation.handling

import android.net.Uri
import io.novafoundation.nova.common.utils.onFailureInstance
import io.novafoundation.nova.feature_deep_linking.presentation.handling.common.DeepLinkHandlingException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

class RootDeepLinkHandler(
    private val pendingDeepLinkProvider: PendingDeepLinkProvider,
    private val nestedHandlers: Collection<DeepLinkHandler>
) : DeepLinkHandler {

    override val callbackFlow: Flow<CallbackEvent> = nestedHandlers
        .mapNotNull { it.callbackFlow }
        .merge()

    override suspend fun matches(data: Uri): Boolean {
        return nestedHandlers.any { it.matches(data) }
    }

    suspend fun checkAndHandlePendingDeepLink(): Result<Unit> {
        val pendingDeepLink = pendingDeepLinkProvider.get() ?: return Result.failure(IllegalStateException("No pending deep link found"))

        return handleDeepLinkInternal(pendingDeepLink)
            .onSuccess { pendingDeepLinkProvider.clear() }
            // A pending link that reaches a handler but fails with a terminal error (malformed/unresolvable),
            // or matches no handler, will never succeed on retry - clear it so it does not replay on every launch.
            .onFailureInstance<DeepLinkHandlingException, Unit> { pendingDeepLinkProvider.clear() }
            .onFailureInstance<HandlerNotFoundException, Unit> { pendingDeepLinkProvider.clear() }
    }

    override suspend fun handleDeepLink(data: Uri): Result<Unit> {
        pendingDeepLinkProvider.save(data)
        return handleDeepLinkInternal(data)
            .onSuccess { pendingDeepLinkProvider.clear() }
            // Same as above: a terminal handling failure must not be persisted, otherwise it bricks every launch.
            .onFailureInstance<DeepLinkHandlingException, Unit> { pendingDeepLinkProvider.clear() }
            .onFailureInstance<HandlerNotFoundException, Unit> { pendingDeepLinkProvider.clear() } // If we haven't find any handler - no need to save deep link
    }

    private suspend fun handleDeepLinkInternal(data: Uri): Result<Unit> {
        val firstHandler = nestedHandlers.find { it.canHandle(data) } ?: return Result.failure(HandlerNotFoundException())

        return firstHandler.handleDeepLink(data)
    }

    private suspend fun DeepLinkHandler.canHandle(data: Uri) = runCatching { this.matches(data) }
        .getOrDefault(false)
}

private class HandlerNotFoundException : Exception()
