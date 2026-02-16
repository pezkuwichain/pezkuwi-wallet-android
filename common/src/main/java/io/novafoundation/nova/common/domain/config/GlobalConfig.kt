package io.novafoundation.nova.common.domain.config

class GlobalConfig(
    val multisigsApiUrl: String,
    val proxyApiUrl: String,
    val multiStakingApiUrl: String,
    val stakingApiOverrides: Map<String, List<String>>
)
