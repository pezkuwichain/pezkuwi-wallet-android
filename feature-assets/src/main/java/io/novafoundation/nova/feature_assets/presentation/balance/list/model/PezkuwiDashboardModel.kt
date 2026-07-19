package io.novafoundation.nova.feature_assets.presentation.balance.list.model

import io.novafoundation.nova.feature_assets.presentation.citizenship.CitizenshipStatus
import java.math.BigInteger

data class PezkuwiDashboardModel(
    val roles: List<String>,
    val trustScore: String,
    val trustScoreRaw: BigInteger,
    val welatiCount: String,
    val citizenshipStatus: CitizenshipStatus = CitizenshipStatus.NOT_STARTED,
    val isTrackingScore: Boolean = false
)
