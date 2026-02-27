package io.novafoundation.nova.feature_assets.presentation.citizenship

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

object PendingCitizenshipReferrer {

    private val _referrerEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val referrerEvent: Flow<String> = _referrerEvent

    fun emit(referrer: String) {
        _referrerEvent.tryEmit(referrer)
    }
}
