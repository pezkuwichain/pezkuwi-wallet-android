package io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.states

import io.novafoundation.nova.common.address.AddressIconGenerator
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_account_api.presenatation.account.wallet.WalletUiUseCase
import io.novafoundation.nova.feature_dapp_impl.domain.DappInteractor
import io.novafoundation.nova.feature_dapp_impl.domain.browser.polkadotJs.PolkadotJsExtensionInteractor
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc.ChainRpc
import io.novafoundation.nova.feature_dapp_impl.web3.polkadotJs.rpc.PolkadotJsRpcBridge
import io.novafoundation.nova.feature_dapp_impl.web3.session.Web3Session
import io.novafoundation.nova.feature_dapp_impl.web3.states.Web3StateMachineHost

class PolkadotJsStateFactory(
    private val interactor: PolkadotJsExtensionInteractor,
    private val commonInteractor: DappInteractor,
    private val resourceManager: ResourceManager,
    private val addressIconGenerator: AddressIconGenerator,
    private val web3Session: Web3Session,
    private val walletUiUseCase: WalletUiUseCase,
    private val chainRpc: ChainRpc,
) {

    fun default(hostApi: Web3StateMachineHost): DefaultPolkadotJsState {
        return DefaultPolkadotJsState(
            interactor = interactor,
            commonInteractor = commonInteractor,
            resourceManager = resourceManager,
            addressIconGenerator = addressIconGenerator,
            web3Session = web3Session,
            hostApi = hostApi,
            walletUiUseCase = walletUiUseCase,
            rpcBridge = PolkadotJsRpcBridge(chainRpc),
        )
    }
}
