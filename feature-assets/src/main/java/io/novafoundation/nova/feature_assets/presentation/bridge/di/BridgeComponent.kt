package io.novafoundation.nova.feature_assets.presentation.bridge.di

import androidx.fragment.app.Fragment
import dagger.BindsInstance
import dagger.Subcomponent
import io.novafoundation.nova.common.di.scope.ScreenScope
import io.novafoundation.nova.feature_assets.presentation.bridge.BridgeFragment

@Subcomponent(
    modules = [
        BridgeModule::class
    ]
)
@ScreenScope
interface BridgeComponent {

    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance fragment: Fragment
        ): BridgeComponent
    }

    fun inject(fragment: BridgeFragment)
}
