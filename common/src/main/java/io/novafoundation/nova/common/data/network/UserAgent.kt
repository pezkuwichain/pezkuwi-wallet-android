package io.novafoundation.nova.common.data.network

object UserAgent {

    const val PEZKUWI = "User-Agent: Pezkuwi Wallet (Android)"

    @Deprecated("Use PEZKUWI instead", replaceWith = ReplaceWith("PEZKUWI"))
    const val NOVA = PEZKUWI
}
