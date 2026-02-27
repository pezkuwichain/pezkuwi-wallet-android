package io.novafoundation.nova.feature_assets.presentation.citizenship.deeplink

import android.net.Uri
import io.novafoundation.nova.common.utils.sequrity.AutomaticInteractionGate
import io.novafoundation.nova.common.utils.sequrity.awaitInteractionAllowed
import io.novafoundation.nova.feature_assets.presentation.citizenship.PendingCitizenshipReferrer
import io.novafoundation.nova.feature_deep_linking.presentation.handling.CallbackEvent
import io.novafoundation.nova.feature_deep_linking.presentation.handling.DeepLinkHandler
import kotlinx.coroutines.flow.MutableSharedFlow

class CitizenshipDeepLinkHandler(
    private val automaticInteractionGate: AutomaticInteractionGate
) : DeepLinkHandler {

    companion object {
        private const val PATH_PREFIX = "/open/citizenship"
        private const val REFERRER_PARAM = "referrer"
    }

    override val callbackFlow = MutableSharedFlow<CallbackEvent>()

    override suspend fun matches(data: Uri): Boolean {
        val path = data.path ?: return false
        return path.startsWith(PATH_PREFIX)
    }

    override suspend fun handleDeepLink(data: Uri): Result<Unit> = runCatching {
        automaticInteractionGate.awaitInteractionAllowed()

        val referrer = data.getQueryParameter(REFERRER_PARAM)
            ?: throw IllegalArgumentException("Missing referrer parameter")

        PendingCitizenshipReferrer.emit(referrer)
    }
}
