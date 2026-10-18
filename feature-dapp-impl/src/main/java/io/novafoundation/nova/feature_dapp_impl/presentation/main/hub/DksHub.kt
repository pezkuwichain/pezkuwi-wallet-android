package io.novafoundation.nova.feature_dapp_impl.presentation.main.hub

import android.net.Uri
import androidx.annotation.StringRes
import io.novafoundation.nova.feature_dapp_impl.R

/*
 * The Dijital Kurdistan Ecosystem Hub: what a user sees after tapping "Dijital Kurdistan"
 * in the DApps tab, before choosing where to go. Four sections, each a grid of apps.
 *
 * Every app opens in the DApp browser. Most are pages of the web app at app.pezkuwichain.io,
 * the same routes its own mobile home links to; the rest are their own sites.
 */

data class DksHubApp(
    val id: String,
    val emoji: String,
    @StringRes val title: Int,
    val url: String,
)

data class DksHubSection(
    val id: String,
    val emoji: String,
    @StringRes val title: Int,
    /** The section's colour, ARGB: its border, glow and header band. */
    val color: Long,
    val apps: List<DksHubApp>,
)

object DksHub {

    /** The DApps list entry that opens the hub instead of a web page (wallet-utils dapps_full.json, "Dijital Kurdistan"). */
    private const val ENTRY_HOST = "pezkuwichain.io"

    const val LOGO_URL = "https://raw.githubusercontent.com/pezkuwichain/pezkuwi-wallet-utils/master/icons/dapps/dapps/DijitalKurdistan.png"

    fun isEntry(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.removePrefix("www.") ?: return false
        val path = uri.path.orEmpty().trim('/')
        return host == ENTRY_HOST && path.isEmpty()
    }

    private const val WEB_APP = "https://app.pezkuwichain.io"
    private const val EXPLORER = "https://explorer.pezkuwichain.io"

    private fun web(route: String) = WEB_APP + route

    val sections: List<DksHubSection> = listOf(
        DksHubSection(
            id = "finance",
            emoji = "💰",
            title = R.string.dks_hub_section_finance,
            color = 0xFF22C55E,
            apps = listOf(
                DksHubApp("wallet", "👛", R.string.dks_hub_app_wallet, EXPLORER),
                DksHubApp("bank", "🏦", R.string.dks_hub_app_bank, web("/finance/bank")),
                DksHubApp("exchange", "💱", R.string.dks_hub_app_exchange, "https://pex.network"),
                DksHubApp("dex", "🔄", R.string.dks_hub_app_dex, web("/dex")),
                DksHubApp("p2p", "🤝", R.string.dks_hub_app_p2p, web("/p2p")),
                DksHubApp("b2b", "🤖", R.string.dks_hub_app_b2b, web("/bereketli")),
                DksHubApp("zekat", "💵", R.string.dks_hub_app_zekat, web("/finance/zekat")),
                DksHubApp("launchpad", "🚀", R.string.dks_hub_app_launchpad, web("/launchpad")),
            )
        ),
        DksHubSection(
            id = "governance",
            emoji = "🏛️",
            title = R.string.dks_hub_section_governance,
            color = 0xFFEF4444,
            apps = listOf(
                DksHubApp("president", "👑", R.string.dks_hub_app_president, web("/elections")),
                DksHubApp("assembly", "🏛️", R.string.dks_hub_app_assembly, web("/governance/assembly")),
                DksHubApp("vote", "🗳️", R.string.dks_hub_app_vote, web("/elections")),
                DksHubApp("validators", "🛡️", R.string.dks_hub_app_validators, EXPLORER),
                DksHubApp("justice", "⚖️", R.string.dks_hub_app_justice, web("/governance/justice")),
                DksHubApp("proposals", "📜", R.string.dks_hub_app_proposals, web("/citizens/government")),
                DksHubApp("polls", "📊", R.string.dks_hub_app_polls, web("/governance/polls")),
                DksHubApp("identity", "🪪", R.string.dks_hub_app_identity, web("/identity")),
                DksHubApp("delegation", "🔗", R.string.dks_hub_app_delegation, "https://wekil.pex.mom"),
            )
        ),
        DksHubSection(
            id = "education",
            emoji = "📚",
            title = R.string.dks_hub_section_education,
            color = 0xFFF59E0B,
            apps = listOf(
                DksHubApp("university", "🎓", R.string.dks_hub_app_university, web("/education/university")),
                DksHubApp("perwerde", "📖", R.string.dks_hub_app_perwerde, web("/education")),
                DksHubApp("certificates", "🏆", R.string.dks_hub_app_certificates, web("/education/certificates")),
                DksHubApp("research", "🔬", R.string.dks_hub_app_research, web("/education/research")),
            )
        ),
        DksHubSection(
            id = "social",
            emoji = "💬",
            title = R.string.dks_hub_section_social,
            color = 0xFF3B82F6,
            apps = listOf(
                DksHubApp("whatskurd", "💬", R.string.dks_hub_app_whatskurd, web("/social/whatskurd")),
                DksHubApp("forum", "📰", R.string.dks_hub_app_forum, web("/forum")),
                DksHubApp("kurdmedia", "📺", R.string.dks_hub_app_kurdmedia, "https://dks.news"),
                DksHubApp("kurdai", "🎙️", R.string.dks_hub_app_kurdai, "https://kurdishtts.dks.news"),
                DksHubApp("games", "🎮", R.string.dks_hub_app_games, "https://games.pex.mom"),
                DksHubApp("music", "🎵", R.string.dks_hub_app_music, "https://music.dks.news"),
                DksHubApp("rewshenbir", "🦅", R.string.dks_hub_app_rewshenbir, "https://rewshenbir.pezkiwi.app"),
                DksHubApp("referral", "👥", R.string.dks_hub_app_referral, web("/dashboard")),
            )
        ),
    )
}
