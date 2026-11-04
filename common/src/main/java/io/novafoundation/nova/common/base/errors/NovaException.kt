package io.novafoundation.nova.common.base.errors

import io.novafoundation.nova.common.R
import io.novafoundation.nova.common.resources.ResourceManager

class NovaException(
    val kind: Kind,
    message: String?,
    exception: Throwable? = null,
) : RuntimeException(message, exception) {

    enum class Kind {
        NETWORK,
        UNEXPECTED
    }

    companion object {

        fun networkError(resourceManager: ResourceManager, throwable: Throwable): NovaException {
            return NovaException(Kind.NETWORK, resourceManager.getString(R.string.connection_error_message_v2_2_0), throwable)
        }
    }
}
