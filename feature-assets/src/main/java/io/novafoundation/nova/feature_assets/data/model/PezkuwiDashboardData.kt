package io.novafoundation.nova.feature_assets.data.model

import java.math.BigInteger

data class PezkuwiDashboardData(
    val roles: List<String>,
    val trustScore: BigInteger,
    val totalReferrals: Int,
    val stakedAmount: BigInteger,
    val perwerdePoints: Int
)
