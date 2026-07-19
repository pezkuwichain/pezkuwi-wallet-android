package io.novafoundation.nova.feature_assets.presentation.bridge.execution.di

import androidx.fragment.app.Fragment
import dagger.BindsInstance
import dagger.Subcomponent
import io.novafoundation.nova.common.di.scope.ScreenScope
import io.novafoundation.nova.feature_assets.presentation.bridge.execution.BridgeExecutionFragment
import io.novafoundation.nova.feature_assets.presentation.bridge.execution.BridgeExecutionPayload

@Subcomponent(
    modules = [
        BridgeExecutionModule::class
    ]
)
@ScreenScope
interface BridgeExecutionComponent {

    @Subcomponent.Factory
    interface Factory {

        fun create(
            @BindsInstance fragment: Fragment,
            @BindsInstance payload: BridgeExecutionPayload
        ): BridgeExecutionComponent
    }

    fun inject(fragment: BridgeExecutionFragment)
}
