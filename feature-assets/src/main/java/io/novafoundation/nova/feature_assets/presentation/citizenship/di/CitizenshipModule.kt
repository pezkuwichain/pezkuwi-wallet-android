package io.novafoundation.nova.feature_assets.presentation.citizenship.di

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.novafoundation.nova.common.di.scope.ScreenScope
import io.novafoundation.nova.common.di.viewmodel.ViewModelKey
import io.novafoundation.nova.common.di.viewmodel.ViewModelModule
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicService
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_assets.data.repository.PezkuwiDashboardRepository
import io.novafoundation.nova.feature_assets.presentation.citizenship.CitizenshipViewModel
import io.novafoundation.nova.runtime.di.REMOTE_STORAGE_SOURCE
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import javax.inject.Named

@Module(includes = [ViewModelModule::class])
class CitizenshipModule {

    @Provides
    @ScreenScope
    fun providePezkuwiDashboardRepository(
        @Named(REMOTE_STORAGE_SOURCE) remoteStorageDataSource: StorageDataSource
    ): PezkuwiDashboardRepository {
        return PezkuwiDashboardRepository(remoteStorageDataSource)
    }

    @Provides
    @IntoMap
    @ViewModelKey(CitizenshipViewModel::class)
    fun provideViewModel(
        extrinsicService: ExtrinsicService,
        chainRegistry: ChainRegistry,
        selectedAccountUseCase: SelectedAccountUseCase,
        resourceManager: ResourceManager,
        pezkuwiDashboardRepository: PezkuwiDashboardRepository
    ): ViewModel {
        return CitizenshipViewModel(
            extrinsicService = extrinsicService,
            chainRegistry = chainRegistry,
            selectedAccountUseCase = selectedAccountUseCase,
            resourceManager = resourceManager,
            pezkuwiDashboardRepository = pezkuwiDashboardRepository
        )
    }

    @Provides
    fun provideViewModelCreator(
        fragment: Fragment,
        viewModelFactory: ViewModelProvider.Factory,
    ): CitizenshipViewModel {
        return ViewModelProvider(fragment, viewModelFactory).get(CitizenshipViewModel::class.java)
    }
}
