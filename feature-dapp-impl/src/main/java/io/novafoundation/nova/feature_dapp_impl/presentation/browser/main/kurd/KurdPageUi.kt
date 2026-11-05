package io.novafoundation.nova.feature_dapp_impl.presentation.browser.main.kurd

import io.novafoundation.nova.runtime.names.KurdName
import io.novafoundation.nova.runtime.names.NameRecord
import io.novafoundation.nova.runtime.names.content.KurdSiteServer.PageState
import java.net.URI

/** What the browser shows for a `.kurd` page: a badge on an open page, or a notice in its place. */
sealed interface KurdPageUi {

    val name: KurdName

    /** [onChainVersion]: a feed showing the release the chain names, as no newer one could be confirmed. */
    class Open(override val name: KurdName, val badge: Badge, val details: Details, val onChainVersion: Boolean = false) : KurdPageUi

    class Notice(override val name: KurdName, val kind: NoticeKind) : KurdPageUi

    enum class Badge {
        /** Granted by Parliament, or under such a name: the state's own. */
        OFFICIAL,

        /** Bought: the content is verified, who made it is not. */
        REGISTERED,

        /** Opened by the reader's choice, on two RPC nodes' word. */
        UNVERIFIED,
    }

    enum class NoticeKind { REFUSED, NEEDS_CONSENT, NOT_REGISTERED, NO_SITE, UNAVAILABLE }

    class Details(
        val badge: Badge,
        /** The finalized block the name was proven at; null when read through RPC or not reported. */
        val provenAtBlock: Long?,
        val owner: Owner,
        val contentId: String,
    )

    sealed interface Owner {
        /** Parliament or an office: a state institution, acting through its multisig. */
        object Institution : Owner

        class Account(val accountId: ByteArray) : Owner
    }

    companion object {

        /** The name a page URL is on, or null for any other page. */
        fun nameOf(pageUrl: String?): KurdName? {
            val host = runCatching { URI(pageUrl ?: return null).host }.getOrNull() ?: return null
            return if (KurdName.isKurdHost(host)) KurdName.parse(host) else null
        }

        /** Null while the page's document has not been answered yet. */
        fun from(name: KurdName, state: PageState?): KurdPageUi? = when (state) {
            null -> null
            is PageState.Open -> {
                val resolution = state.resolution
                val badge = when {
                    !resolution.verified -> Badge.UNVERIFIED
                    resolution.official -> Badge.OFFICIAL
                    else -> Badge.REGISTERED
                }
                val owner = when (val owner = resolution.record.owner) {
                    is NameRecord.Owner.Account -> Owner.Account(owner.accountId)
                    is NameRecord.Owner.Office, NameRecord.Owner.Governance -> Owner.Institution
                }
                val provenAt = resolution.block.takeIf { resolution.verified }

                Open(name, badge, Details(badge, provenAt, owner, state.root.toString()), state.onChainVersion)
            }
            is PageState.NeedsConsent -> Notice(name, NoticeKind.NEEDS_CONSENT)
            PageState.Refused -> Notice(name, NoticeKind.REFUSED)
            PageState.NotRegistered -> Notice(name, NoticeKind.NOT_REGISTERED)
            PageState.NoSite -> Notice(name, NoticeKind.NO_SITE)
            PageState.Unavailable -> Notice(name, NoticeKind.UNAVAILABLE)
        }
    }
}
