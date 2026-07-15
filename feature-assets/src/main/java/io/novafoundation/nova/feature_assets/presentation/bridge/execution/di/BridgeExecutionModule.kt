package io.novafoundation.nova.feature_assets.presentation.bridge.execution.di

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.novafoundation.nova.common.di.viewmodel.ViewModelKey
import io.novafoundation.nova.common.di.viewmodel.ViewModelModule
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_assets.domain.WalletInteractor
import io.novafoundation.nova.feature_assets.domain.send.SendInteractor
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_assets.presentation.bridge.execution.BridgeExecutionPayload
import io.novafoundation.nova.feature_assets.presentation.bridge.execution.BridgeExecutionViewModel
import io.novafoundation.nova.feature_wallet_api.domain.SendUseCase
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry

@Module(includes = [ViewModelModule::class])
class BridgeExecutionModule {

    @Provides
    @IntoMap
    @ViewModelKey(BridgeExecutionViewModel::class)
    fun provideViewModel(
        payload: BridgeExecutionPayload,
        resourceManager: ResourceManager,
        chainRegistry: ChainRegistry,
        walletInteractor: WalletInteractor,
        sendInteractor: SendInteractor,
        sendUseCase: SendUseCase,
        selectedAccountUseCase: SelectedAccountUseCase,
        router: AssetsRouter,
    ): ViewModel {
        return BridgeExecutionViewModel(
            payload,
            resourceManager,
            chainRegistry,
            walletInteractor,
            sendInteractor,
            sendUseCase,
            selectedAccountUseCase,
            router
        )
    }

    @Provides
    fun provideViewModelCreator(
        fragment: Fragment,
        viewModelFactory: ViewModelProvider.Factory
    ): BridgeExecutionViewModel {
        return ViewModelProvider(fragment, viewModelFactory).get(BridgeExecutionViewModel::class.java)
    }
}
