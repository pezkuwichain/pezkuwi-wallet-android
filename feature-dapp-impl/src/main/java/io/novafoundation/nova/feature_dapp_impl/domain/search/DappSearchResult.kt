package io.novafoundation.nova.feature_dapp_impl.domain.search

import io.novafoundation.nova.feature_dapp_api.data.model.DApp

sealed interface DappSearchResult {

    val isTrustedByNova: Boolean

    class Url(val url: String, override val isTrustedByNova: Boolean) : DappSearchResult

    class Search(val query: String, val searchUrl: String) : DappSearchResult {
        override val isTrustedByNova: Boolean = false
    }

    /** A page under a `.kurd` name: resolved on chain when opened, never looked up on the web. */
    class KurdName(
        val name: io.novafoundation.nova.runtime.names.KurdName,
        val path: String = "/"
    ) : DappSearchResult {
        override val isTrustedByNova: Boolean = true

        val url: String get() = "https://${name.name}$path"
    }

    class Dapp(val dapp: DApp) : DappSearchResult {
        override val isTrustedByNova: Boolean = true
    }
}
