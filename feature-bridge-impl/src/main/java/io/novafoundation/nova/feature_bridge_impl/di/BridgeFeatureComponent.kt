package io.novafoundation.nova.feature_bridge_impl.di

import dagger.Component
import io.novafoundation.nova.common.di.CommonApi
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.feature_bridge_api.di.BridgeFeatureApi
import io.novafoundation.nova.feature_bridge_impl.presentation.deposit.BridgeDepositFragment

@Component(
    dependencies = [
        BridgeFeatureDependencies::class
    ],
    modules = [
        BridgeFeatureModule::class
    ]
)
@FeatureScope
interface BridgeFeatureComponent : BridgeFeatureApi {

    fun inject(fragment: BridgeDepositFragment)

    @Component.Factory
    interface Factory {
        fun create(
            dependencies: BridgeFeatureDependencies
        ): BridgeFeatureComponent
    }

    @Component(dependencies = [CommonApi::class])
    interface BridgeFeatureDependenciesComponent : BridgeFeatureDependencies
}
