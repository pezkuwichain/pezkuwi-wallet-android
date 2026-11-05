package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.states

import io.novafoundation.nova.common.address.AddressIconGenerator
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_account_api.presenatation.account.wallet.WalletUiUseCase
import io.novafoundation.nova.feature_dapp_impl.R
import io.novafoundation.nova.feature_dapp_impl.domain.DappInteractor
import io.novafoundation.nova.feature_dapp_impl.domain.browser.polkadotJs.PolkadotJsExtensionInteractor
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.PolkadotJsTransportRequest
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.model.mapPolkadotJsSignerPayloadToPolkadotPayload
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc.PolkadotJsRpcBridge
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc.isKurdPage
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc.sameOrigin
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc.until
import io.novafoundation.nova.feature_dapp_impl.web3.session.Web3Session
import io.novafoundation.nova.feature_dapp_impl.web3.states.BaseState
import io.novafoundation.nova.feature_dapp_impl.web3.states.Web3ExtensionStateMachine.ExternalEvent
import io.novafoundation.nova.feature_dapp_impl.web3.states.Web3ExtensionStateMachine.StateMachineTransition
import io.novafoundation.nova.feature_dapp_impl.web3.states.Web3StateMachineHost
import io.novafoundation.nova.feature_dapp_impl.web3.states.Web3StateMachineHost.NotAuthorizedException
import io.novafoundation.nova.feature_dapp_impl.web3.states.hostApi.ConfirmTxResponse
import io.novafoundation.nova.feature_external_sign_api.model.signPayload.ExternalSignRequest
import io.novafoundation.nova.feature_external_sign_api.model.signPayload.polkadot.PolkadotSignerResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class DefaultPolkadotJsState(
    private val interactor: PolkadotJsExtensionInteractor,
    commonInteractor: DappInteractor,
    resourceManager: ResourceManager,
    addressIconGenerator: AddressIconGenerator,
    web3Session: Web3Session,
    hostApi: Web3StateMachineHost,
    walletUiUseCase: WalletUiUseCase,
    private val rpcBridge: PolkadotJsRpcBridge,
) : BaseState<PolkadotJsTransportRequest<*>, PolkadotJsState>(
    commonInteractor = commonInteractor,
    resourceManager = resourceManager,
    addressIconGenerator = addressIconGenerator,
    web3Session = web3Session,
    hostApi = hostApi,
    walletUiUseCase = walletUiUseCase
),
    PolkadotJsState {

    override suspend fun acceptRequest(request: PolkadotJsTransportRequest<*>, transition: StateMachineTransition<PolkadotJsState>) {
        when (request) {
            is PolkadotJsTransportRequest.Single.AuthorizeTab -> authorizeTab(request)
            is PolkadotJsTransportRequest.Single.ListAccounts -> supplyAccountList(request)
            is PolkadotJsTransportRequest.Single.Sign -> signExtrinsicIfAllowed(request, getAuthorizationStateForCurrentPage())
            is PolkadotJsTransportRequest.Subscription.SubscribeAccounts -> supplyAccountListSubscription(request)
            is PolkadotJsTransportRequest.Single.ListMetadata -> suppleKnownMetadatas(request)
            is PolkadotJsTransportRequest.Single.ProvideMetadata -> handleProvideMetadata(request)
            is PolkadotJsTransportRequest.Rpc<*> -> handleRpc(request)
        }
    }

    override suspend fun acceptEvent(event: ExternalEvent, transition: StateMachineTransition<PolkadotJsState>) {
        when (event) {
            ExternalEvent.PhishingDetected -> transition.emitState(PhishingDetectedPolkadotJsState())
        }
    }

    private suspend fun authorizeTab(request: PolkadotJsTransportRequest.Single.AuthorizeTab) {
        val authorized = authorizeDapp()

        request.accept(authorized)
    }

    private suspend fun signExtrinsicIfAllowed(request: PolkadotJsTransportRequest.Single.Sign, state: Web3Session.Authorization.State) {
        when (state) {
            // request user confirmation if dapp is authorized
            Web3Session.Authorization.State.ALLOWED -> signExtrinsicWithConfirmation(request)
            // reject otherwise
            else -> request.reject(NotAuthorizedException)
        }
    }

    private suspend fun signExtrinsicWithConfirmation(request: PolkadotJsTransportRequest.Single.Sign) {
        val signRequest = ExternalSignRequest.Polkadot(request.requestId, mapPolkadotJsSignerPayloadToPolkadotPayload(request.signerPayload))

        when (val response = hostApi.confirmTx(signRequest)) {
            is ConfirmTxResponse.Rejected -> request.reject(NotAuthorizedException)
            is ConfirmTxResponse.Sent -> throw IllegalStateException("Unexpected 'Sent' response for PolkadotJs extension")
            is ConfirmTxResponse.Signed -> request.accept(PolkadotSignerResult(response.requestId, response.signature, response.modifiedTransaction))
            is ConfirmTxResponse.SigningFailed -> {
                if (response.shouldPresent) hostApi.showError(resourceManager.getString(R.string.dapp_sign_extrinsic_failed))

                request.reject(Web3StateMachineHost.SigningFailedException)
            }

            is ConfirmTxResponse.ChainIsDisabled -> {
                hostApi.showError(
                    resourceManager.getString(R.string.disabled_chain_error_title, response.chainName),
                    resourceManager.getString(R.string.disabled_chain_error_message, response.chainName)
                )

                request.reject(Web3StateMachineHost.SigningFailedException)
            }
        }
    }

    /**
     * Chain calls are offered to `.kurd` pages only, judged by the page the tab shows rather than
     * anything the page says: elsewhere a page opens its own connections. A subscription ends when
     * the tab leaves the page's origin.
     */
    private suspend fun handleRpc(request: PolkadotJsTransportRequest.Rpc<*>) {
        val pageUrl = hostApi.currentPageAnalyzed.first().url
        if (!isKurdPage(pageUrl)) {
            if (request is PolkadotJsTransportRequest.Rpc.ListProviders) request.accept(emptyMap()) else request.reject(NotAuthorizedException)
            return
        }

        runCatching {
            when (request) {
                is PolkadotJsTransportRequest.Rpc.ListProviders -> request.accept(rpcBridge.providers())
                is PolkadotJsTransportRequest.Rpc.StartProvider -> request.accept(rpcBridge.start(request.key))
                is PolkadotJsTransportRequest.Rpc.Send -> request.scope.launch {
                    runCatching { rpcBridge.send(request.method, request.params) }
                        .onSuccess(request::accept)
                        .onFailure(request::reject)
                }
                is PolkadotJsTransportRequest.Rpc.Subscribe -> {
                    val updates = rpcBridge.subscribe(request.method, request.params)
                    request.accept(updates.until { hostApi.currentPageAnalyzed.first { !sameOrigin(it.url, pageUrl) } })
                }
                is PolkadotJsTransportRequest.Rpc.SubscribeConnected -> request.accept(flowOf(true))
            }
        }.onFailure(request::reject)
    }

    private suspend fun handleProvideMetadata(request: PolkadotJsTransportRequest.Single.ProvideMetadata) = request.respondIfAllowed {
        false // we do not accept provided metadata since app handles metadata sync by its own
    }

    private suspend fun suppleKnownMetadatas(request: PolkadotJsTransportRequest.Single.ListMetadata) = request.respondIfAllowed {
        interactor.getKnownInjectedMetadatas()
    }

    private suspend fun supplyAccountList(request: PolkadotJsTransportRequest.Single.ListAccounts) = request.respondIfAllowed {
        interactor.getInjectedAccounts()
    }

    private suspend fun supplyAccountListSubscription(request: PolkadotJsTransportRequest.Subscription.SubscribeAccounts) {
        request.respondIfAllowed {
            flowOf(interactor.getInjectedAccounts())
        }
    }

    private suspend fun <T> PolkadotJsTransportRequest<T>.respondIfAllowed(
        responseConstructor: suspend () -> T
    ) = respondIfAllowed(
        ifAllowed = { accept(responseConstructor()) }
    ) { reject(NotAuthorizedException) }
}
