package io.novafoundation.nova.feature_assets.presentation.bridge.di

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.novafoundation.nova.common.di.viewmodel.ViewModelKey
import io.novafoundation.nova.common.di.viewmodel.ViewModelModule
import io.novafoundation.nova.common.presentation.AssetIconProvider
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicService
import io.novafoundation.nova.feature_account_api.data.multisig.MultisigPendingOperationsService
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_assets.domain.WalletInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeMultisigInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.RealBridgeMultisigInteractor
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_assets.presentation.bridge.BridgeViewModel
import io.novafoundation.nova.feature_multisig_operations.presentation.callFormatting.MultisigCallFormatter
import io.novafoundation.nova.runtime.di.REMOTE_STORAGE_SOURCE
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import javax.inject.Named

@Module(includes = [ViewModelModule::class])
class BridgeModule {

    @Provides
    fun provideBridgeMultisigInteractor(
        chainRegistry: ChainRegistry,
        selectedAccountUseCase: SelectedAccountUseCase,
        @Named(REMOTE_STORAGE_SOURCE) storageDataSource: StorageDataSource,
        extrinsicService: ExtrinsicService
    ): BridgeMultisigInteractor {
        return RealBridgeMultisigInteractor(chainRegistry, selectedAccountUseCase, storageDataSource, extrinsicService)
    }

    @Provides
    @IntoMap
    @ViewModelKey(BridgeViewModel::class)
    fun provideViewModel(
        router: AssetsRouter,
        resourceManager: ResourceManager,
        chainRegistry: ChainRegistry,
        assetIconProvider: AssetIconProvider,
        walletInteractor: WalletInteractor,
        bridgeMultisigInteractor: BridgeMultisigInteractor,
        multisigPendingOperationsService: MultisigPendingOperationsService,
        multisigCallFormatter: MultisigCallFormatter,
        selectedAccountUseCase: SelectedAccountUseCase,
    ): ViewModel {
        return BridgeViewModel(
            router,
            resourceManager,
            chainRegistry,
            assetIconProvider,
            walletInteractor,
            bridgeMultisigInteractor,
            multisigPendingOperationsService,
            multisigCallFormatter,
            selectedAccountUseCase,
        )
    }

    @Provides
    fun provideViewModelCreator(
        fragment: Fragment,
        viewModelFactory: ViewModelProvider.Factory
    ): BridgeViewModel {
        return ViewModelProvider(fragment, viewModelFactory).get(BridgeViewModel::class.java)
    }
}
