package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs

import android.util.Log
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.feature_dapp_impl.web3.Web3Responder
import io.novafoundation.nova.feature_dapp_impl.web3.states.Web3StateMachineHost
import io.novafoundation.nova.feature_dapp_impl.web3.webview.WebViewHolder
import org.json.JSONObject

class PolkadotJsResponder(
    private val webViewHolder: WebViewHolder
) : Web3Responder {

    override fun respondResult(id: String, result: String) {
        evaluateJs(successResponse(id, result))
    }

    override fun respondSubscription(id: String, result: String) {
        evaluateJs(successSubscription(id, result))
    }

    override fun respondError(id: String, error: Throwable) {
        evaluateJs(failure(id, error))
    }

    private fun evaluateJs(js: String) = webViewHolder.webView?.post {
        webViewHolder.webView?.evaluateJavascript(js, null)

        log(js)
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, message)
    }

    private fun successResponse(id: String, result: String) = "window.walletExtension.onAppResponse(\"$id\", $result, null)"

    private fun successSubscription(id: String, result: String) = "window.walletExtension.onAppSubscription(\"$id\", $result)"

    private fun failure(id: String, error: Throwable): String {
        val message = JSONObject.quote(publicErrorMessage(error))

        return "window.walletExtension.onAppResponse(\"$id\", null, new Error($message))"
    }

    /**
     * The page is untrusted, so it only ever receives a fixed message per known rejection reason,
     * never the text of an arbitrary internal exception.
     */
    private fun publicErrorMessage(error: Throwable): String = when (error) {
        Web3StateMachineHost.NotAuthorizedException -> "Rejected by user"
        Web3StateMachineHost.SigningFailedException -> "Signing failed"
        Web3StateMachineHost.PhishingDetectedException -> "Phishing detected!"
        else -> "Request failed"
    }
}
