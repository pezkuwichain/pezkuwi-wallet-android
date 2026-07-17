package io.novafoundation.nova.feature_assets.presentation.bridge.di

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.novafoundation.nova.common.data.config.GlobalConfigDataSource
import io.novafoundation.nova.common.data.network.NetworkApiCreator
import io.novafoundation.nova.common.di.viewmodel.ViewModelKey
import io.novafoundation.nova.common.di.viewmodel.ViewModelModule
import io.novafoundation.nova.common.presentation.AssetIconProvider
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicService
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_assets.domain.WalletInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeMultisigInteractor
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.BridgeMultisigOperationsApi
import io.novafoundation.nova.feature_assets.domain.bridge.multisig.RealBridgeMultisigInteractor
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_assets.presentation.bridge.BridgeViewModel
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.AssetSourceRegistry
import io.novafoundation.nova.runtime.di.REMOTE_STORAGE_SOURCE
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import javax.inject.Named

@Module(includes = [ViewModelModule::class])
class BridgeModule {

    @Provides
    fun provideBridgeMultisigOperationsApi(apiCreator: NetworkApiCreator): BridgeMultisigOperationsApi {
        return apiCreator.create(BridgeMultisigOperationsApi::class.java)
    }

    @Provides
    fun provideBridgeMultisigInteractor(
        chainRegistry: ChainRegistry,
        selectedAccountUseCase: SelectedAccountUseCase,
        @Named(REMOTE_STORAGE_SOURCE) storageDataSource: StorageDataSource,
        extrinsicService: ExtrinsicService,
        bridgeMultisigOperationsApi: BridgeMultisigOperationsApi,
        globalConfigDataSource: GlobalConfigDataSource,
    ): BridgeMultisigInteractor {
        return RealBridgeMultisigInteractor(
            chainRegistry,
            selectedAccountUseCase,
            storageDataSource,
            extrinsicService,
            bridgeMultisigOperationsApi,
            globalConfigDataSource,
        )
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
        assetSourceRegistry: AssetSourceRegistry,
    ): ViewModel {
        return BridgeViewModel(
            router,
            resourceManager,
            chainRegistry,
            assetIconProvider,
            walletInteractor,
            bridgeMultisigInteractor,
            assetSourceRegistry,
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
