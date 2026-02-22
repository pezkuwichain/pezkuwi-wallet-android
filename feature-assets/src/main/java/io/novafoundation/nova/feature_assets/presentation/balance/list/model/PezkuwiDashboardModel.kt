package io.novafoundation.nova.feature_assets.presentation.balance.list.model

import io.novafoundation.nova.feature_assets.presentation.citizenship.CitizenshipStatus

data class PezkuwiDashboardModel(
    val roles: List<String>,
    val trustScore: String,
    val welatiCount: String,
    val citizenshipStatus: CitizenshipStatus = CitizenshipStatus.NOT_STARTED
)
