package io.novafoundation.nova.feature_deep_linking.di

import dagger.Module
import dagger.Provides
import io.novafoundation.nova.common.data.storage.Preferences
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.feature_deep_linking.R
import io.novafoundation.nova.feature_deep_linking.presentation.configuring.LinkBuilderFactory
import io.novafoundation.nova.feature_deep_linking.presentation.handling.PendingDeepLinkProvider
import io.novafoundation.nova.feature_deep_linking.presentation.handling.common.DeepLinkingPreferences

@Module
class DeepLinkingFeatureModule {

    @Provides
    @FeatureScope
    fun provideDeepLinkPreferences(
        resourceManager: ResourceManager
    ) = DeepLinkingPreferences(
        deepLinkScheme = resourceManager.getString(R.string.deep_linking_scheme),
        deepLinkHost = resourceManager.getString(R.string.deep_linking_host),
        appLinkHost = resourceManager.getString(R.string.app_link_host)
    )

    @Provides
    @FeatureScope
    fun provideLinkBuilderFactory(preferences: DeepLinkingPreferences) = LinkBuilderFactory(preferences)

    @Provides
    @FeatureScope
    fun providePendingDeepLinkProvider(preferences: Preferences): PendingDeepLinkProvider {
        return PendingDeepLinkProvider(preferences)
    }

}
