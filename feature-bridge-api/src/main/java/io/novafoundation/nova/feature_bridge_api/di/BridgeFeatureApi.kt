package io.novafoundation.nova.feature_bridge_api.di

import io.novafoundation.nova.feature_bridge_api.domain.model.BridgeConfig

interface BridgeFeatureApi {

    val bridgeConfig: BridgeConfig
}
