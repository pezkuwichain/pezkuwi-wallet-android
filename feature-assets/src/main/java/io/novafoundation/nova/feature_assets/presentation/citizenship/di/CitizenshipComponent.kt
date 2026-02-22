package io.novafoundation.nova.feature_assets.presentation.citizenship.di

import androidx.fragment.app.Fragment
import dagger.BindsInstance
import dagger.Subcomponent
import io.novafoundation.nova.common.di.scope.ScreenScope
import io.novafoundation.nova.feature_assets.presentation.citizenship.CitizenshipBottomSheet

@Subcomponent(
    modules = [
        CitizenshipModule::class
    ]
)
@ScreenScope
interface CitizenshipComponent {

    @Subcomponent.Factory
    interface Factory {

        fun create(
            @BindsInstance fragment: Fragment
        ): CitizenshipComponent
    }

    fun inject(fragment: CitizenshipBottomSheet)
}
