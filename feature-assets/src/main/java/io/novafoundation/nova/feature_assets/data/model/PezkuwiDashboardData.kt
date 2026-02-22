package io.novafoundation.nova.feature_assets.data.model

import io.novafoundation.nova.feature_assets.presentation.citizenship.CitizenshipStatus
import java.math.BigInteger

data class PezkuwiDashboardData(
    val roles: List<String>,
    val trustScore: BigInteger,
    val welatiCount: Int,
    val citizenshipStatus: CitizenshipStatus = CitizenshipStatus.NOT_STARTED
)
