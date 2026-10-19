package io.novafoundation.nova.feature_governance_impl.presentation.referenda.details.deeplink

import android.net.Uri
import io.novafoundation.nova.common.utils.sequrity.AutomaticInteractionGate
import io.novafoundation.nova.feature_governance_api.data.MutableGovernanceState
import io.novafoundation.nova.feature_governance_impl.presentation.GovernanceRouter
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.test_shared.whenever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ReferendumDeepLinkHandlerTest {

    private val handler = ReferendumDeepLinkHandler(
        router = mock(GovernanceRouter::class.java),
        chainRegistry = mock(ChainRegistry::class.java),
        mutableGovernanceState = mock(MutableGovernanceState::class.java),
        automaticInteractionGate = mock(AutomaticInteractionGate::class.java)
    )

    private fun linkWithPath(path: String): Uri = mock(Uri::class.java).also {
        whenever(it.path).thenReturn(path)
    }

    @Test
    fun `matches a referendum link`() = runBlocking {
        assertTrue(handler.matches(linkWithPath("/open/gov")))
    }

    // "/open/governance" starts with the referendum prefix "/open/gov"; matching it sent the
    // governance banner link to the referendum screen, which failed without a referendum id.
    @Test
    fun `does not match a link that only starts with the referendum path`() = runBlocking {
        assertFalse(handler.matches(linkWithPath("/open/governance")))
    }
}
