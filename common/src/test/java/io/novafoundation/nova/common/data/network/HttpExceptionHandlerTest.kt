package io.novafoundation.nova.common.data.network

import io.novafoundation.nova.common.R
import io.novafoundation.nova.common.base.BaseException
import io.novafoundation.nova.common.base.errors.NovaException
import io.novafoundation.nova.common.resources.ResourceManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException

class HttpExceptionHandlerTest {

    private val resourceManager = Mockito.mock(ResourceManager::class.java).also {
        Mockito.`when`(it.getString(R.string.common_undefined_error_message)).thenReturn(UNDEFINED)
        Mockito.`when`(it.getString(R.string.connection_error_message_v2_2_0)).thenReturn(CONNECTION)
    }

    private val handler = HttpExceptionHandler(resourceManager)

    @Test
    fun `an unexpected error without a message gets the common error text`() {
        val error = handler.transformException(IllegalStateException())

        assertEquals(BaseException.Kind.UNEXPECTED, error.kind)
        assertEquals(UNDEFINED, error.message)
    }

    @Test
    fun `an unexpected error keeps its own message`() {
        assertEquals("boom", handler.transformException(IllegalStateException("boom")).message)
    }

    @Test
    fun `a socket network error carries the connection error text`() {
        val error = NovaException.networkError(resourceManager, IOException())

        assertEquals(NovaException.Kind.NETWORK, error.kind)
        assertEquals(CONNECTION, error.message)
    }

    private companion object {
        const val UNDEFINED = "undefined"
        const val CONNECTION = "connection"
    }
}
