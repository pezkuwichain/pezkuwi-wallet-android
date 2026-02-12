package io.novafoundation.nova.feature_bridge_impl.di

import dagger.Module
import dagger.Provides
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.feature_bridge_api.domain.model.BridgeConfig

@Module
class BridgeFeatureModule {

    @Provides
    @FeatureScope
    fun provideBridgeConfig(): BridgeConfig = BridgeConfig.DEFAULT
}
