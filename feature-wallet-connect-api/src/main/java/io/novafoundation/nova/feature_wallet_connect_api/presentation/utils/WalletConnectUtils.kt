package io.novafoundation.nova.feature_wallet_connect_api.presentation.utils

import android.net.Uri

object WalletConnectUtils {
    fun isWalletConnectPairingLink(data: Uri): Boolean {
        val isPezkuwiLink = data.scheme == "pezkuwiwallet" && data.host == "wc"
        val isLinkFromOtherSource = data.scheme == "wc"
        val isWalletConnectLink = isPezkuwiLink || isLinkFromOtherSource

        val isPairing = "symKey" in data.toString()
        return isWalletConnectLink && isPairing
    }
}
