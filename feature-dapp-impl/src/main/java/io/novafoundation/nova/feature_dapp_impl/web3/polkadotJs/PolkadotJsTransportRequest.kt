package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs

import com.google.gson.Gson
import io.novafoundation.nova.common.utils.inBackground
import io.novafoundation.nova.feature_dapp_impl.web3.Web3Responder
import io.novafoundation.nova.feature_dapp_impl.web3.Web3Transport
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.model.InjectedAccount
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.model.InjectedMetadataKnown
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.model.SignerPayload
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc.ProviderMeta
import io.novafoundation.nova.feature_external_sign_api.model.signPayload.polkadot.PolkadotSignerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

sealed class PolkadotJsTransportRequest<R>(
    protected val web3Responder: Web3Responder,
    protected val identifier: Identifier,
    val url: String,
    val requestId: String,
) : Web3Transport.Request<R> {

    /** [url] is the sender's origin as the WebView reported it; the page's own claim is not read. */
    override val origin: String get() = url

    override fun reject(error: Throwable) {
        web3Responder.respondError(requestId, error)
    }

    enum class Identifier(val id: String) {
        AUTHORIZE_TAB("pub(authorize.tab)"),
        LIST_ACCOUNTS("pub(accounts.list)"),
        SIGN_EXTRINSIC("pub(extrinsic.sign)"),
        SUBSCRIBE_ACCOUNTS("pub(accounts.subscribe)"),
        LIST_METADATA("pub(metadata.list)"),
        PROVIDE_METADATA("pub(metadata.provide)"),
        SIGN_BYTES("pub(bytes.sign)"),
        RPC_LIST_PROVIDERS("pub(rpc.listProviders)"),
        RPC_START_PROVIDER("pub(rpc.startProvider)"),
        RPC_SEND("pub(rpc.send)"),
        RPC_SUBSCRIBE("pub(rpc.subscribe)"),
        RPC_SUBSCRIBE_CONNECTED("pub(rpc.subscribeConnected)"),
    }

    sealed class Single<R>(
        web3Responder: Web3Responder,
        url: String,
        identifier: Identifier,
        requestId: String
    ) : PolkadotJsTransportRequest<R>(web3Responder, identifier, url, requestId) {

        abstract fun serializeResponse(response: R): String

        override fun accept(response: R) {
            web3Responder.respondResult(requestId, serializeResponse(response))
        }

        class AuthorizeTab(
            web3Responder: Web3Responder,
            url: String,
            requestId: String
        ) : Single<Boolean>(web3Responder, url, Identifier.AUTHORIZE_TAB, requestId) {

            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun serializeResponse(authorized: Boolean): String {
                return authorized.toString()
            }
        }

        class ListAccounts(
            web3Responder: Web3Responder,
            url: String,
            private val gson: Gson,
            requestId: String
        ) : Single<List<InjectedAccount>>(web3Responder, url, Identifier.LIST_ACCOUNTS, requestId) {

            override fun serializeResponse(response: List<InjectedAccount>): String {
                return gson.toJson(response)
            }
        }

        sealed class Sign(
            web3Responder: Web3Responder,
            url: String,
            requestId: String,
            val signerPayload: SignerPayload,
            private val gson: Gson,
            identifier: Identifier,
        ) : Single<PolkadotSignerResult>(web3Responder, url, identifier, requestId) {

            override fun serializeResponse(response: PolkadotSignerResult): String {
                return gson.toJson(response)
            }

            class Extrinsic(
                web3Responder: Web3Responder,
                url: String,
                requestId: String,
                signerPayload: SignerPayload.Json,
                gson: Gson,
            ) : Sign(web3Responder, url, requestId, signerPayload, gson, Identifier.SIGN_EXTRINSIC)

            class Bytes(
                web3Responder: Web3Responder,
                url: String,
                requestId: String,
                signerPayload: SignerPayload.Raw,
                gson: Gson,
            ) : Sign(web3Responder, url, requestId, signerPayload, gson, Identifier.SIGN_BYTES)
        }

        class ListMetadata(
            web3Responder: Web3Responder,
            url: String,
            private val gson: Gson,
            requestId: String
        ) : Single<List<InjectedMetadataKnown>>(web3Responder, url, Identifier.LIST_METADATA, requestId) {

            override fun serializeResponse(response: List<InjectedMetadataKnown>): String {
                return gson.toJson(response)
            }
        }

        class ProvideMetadata(
            web3Responder: Web3Responder,
            url: String,
            requestId: String
        ) : Single<Boolean>(web3Responder, url, Identifier.PROVIDE_METADATA, requestId) {

            override fun serializeResponse(response: Boolean): String {
                return response.toString()
            }
        }
    }

    /** The provider half of the extension API: the page's chain calls, answered over the wallet's connection. */
    sealed class Rpc<R>(
        web3Responder: Web3Responder,
        url: String,
        identifier: Identifier,
        requestId: String,
    ) : PolkadotJsTransportRequest<R>(web3Responder, identifier, url, requestId) {

        class ListProviders(
            web3Responder: Web3Responder,
            url: String,
            requestId: String,
            private val gson: Gson,
        ) : Rpc<Map<String, ProviderMeta>>(web3Responder, url, Identifier.RPC_LIST_PROVIDERS, requestId) {

            override fun accept(response: Map<String, ProviderMeta>) {
                web3Responder.respondResult(requestId, gson.toJson(response))
            }
        }

        class StartProvider(
            web3Responder: Web3Responder,
            url: String,
            requestId: String,
            val key: String,
            private val gson: Gson,
        ) : Rpc<ProviderMeta>(web3Responder, url, Identifier.RPC_START_PROVIDER, requestId) {

            override fun accept(response: ProviderMeta) {
                web3Responder.respondResult(requestId, gson.toJson(response))
            }
        }

        /** Answered off the request queue: a slow node must not hold up the page's other calls. */
        class Send(
            val scope: CoroutineScope,
            web3Responder: Web3Responder,
            url: String,
            requestId: String,
            val method: String,
            val params: List<Any>,
            private val gson: Gson,
        ) : Rpc<Any?>(web3Responder, url, Identifier.RPC_SEND, requestId) {

            override fun accept(response: Any?) {
                web3Responder.respondResult(requestId, gson.toJson(response))
            }
        }

        /** Answers with a subscription id, then sends each notification's result to the page's callback. */
        class Subscribe(
            private val scope: CoroutineScope,
            web3Responder: Web3Responder,
            url: String,
            requestId: String,
            val method: String,
            val params: List<Any>,
            private val gson: Gson,
        ) : Rpc<Flow<Any?>>(web3Responder, url, Identifier.RPC_SUBSCRIBE, requestId) {

            override fun accept(response: Flow<Any?>) {
                web3Responder.respondResult(requestId, gson.toJson(requestId))

                response
                    .onEach { web3Responder.respondSubscription(requestId, gson.toJson(it)) }
                    .catch { web3Responder.respondError(requestId, it) }
                    .inBackground()
                    .launchIn(scope)
            }
        }

        class SubscribeConnected(
            private val scope: CoroutineScope,
            web3Responder: Web3Responder,
            url: String,
            requestId: String,
        ) : Rpc<Flow<Boolean>>(web3Responder, url, Identifier.RPC_SUBSCRIBE_CONNECTED, requestId) {

            override fun accept(response: Flow<Boolean>) {
                web3Responder.respondResult(requestId, "true")

                response
                    .onEach { web3Responder.respondSubscription(requestId, it.toString()) }
                    .inBackground()
                    .launchIn(scope)
            }
        }
    }

    sealed class Subscription<R>(
        private val scope: CoroutineScope,
        requestId: String,
        web3Responder: Web3Responder,
        url: String,
        identifier: Identifier
    ) : PolkadotJsTransportRequest<Flow<R>>(web3Responder, identifier, url, requestId) {

        abstract fun serializeSubscriptionResponse(response: R): String

        override fun accept(response: Flow<R>) {
            web3Responder.respondResult(requestId, "true")

            response
                .map(::serializeSubscriptionResponse)
                .onEach { web3Responder.respondSubscription(requestId, it) }
                .inBackground()
                .launchIn(scope)
        }

        class SubscribeAccounts(
            scope: CoroutineScope,
            requestId: String,
            web3Responder: Web3Responder,
            url: String,
            private val gson: Gson,
        ) : Subscription<List<InjectedAccount>>(scope, requestId, web3Responder, url, Identifier.SUBSCRIBE_ACCOUNTS) {

            override fun serializeSubscriptionResponse(response: List<InjectedAccount>): String {
                return gson.toJson(response)
            }
        }
    }
}
