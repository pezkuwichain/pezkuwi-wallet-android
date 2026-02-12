package io.novafoundation.nova.feature_assets.presentation.bridge.di

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.novafoundation.nova.common.di.viewmodel.ViewModelKey
import io.novafoundation.nova.common.di.viewmodel.ViewModelModule
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_assets.presentation.AssetsRouter
import io.novafoundation.nova.feature_assets.presentation.bridge.BridgeViewModel
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry

@Module(includes = [ViewModelModule::class])
class BridgeModule {

    @Provides
    @IntoMap
    @ViewModelKey(BridgeViewModel::class)
    fun provideViewModel(
        router: AssetsRouter,
        resourceManager: ResourceManager,
        chainRegistry: ChainRegistry
    ): ViewModel {
        return BridgeViewModel(router, resourceManager, chainRegistry)
    }

    @Provides
    fun provideViewModelCreator(
        fragment: Fragment,
        viewModelFactory: ViewModelProvider.Factory
    ): BridgeViewModel {
        return ViewModelProvider(fragment, viewModelFactory).get(BridgeViewModel::class.java)
    }
}
