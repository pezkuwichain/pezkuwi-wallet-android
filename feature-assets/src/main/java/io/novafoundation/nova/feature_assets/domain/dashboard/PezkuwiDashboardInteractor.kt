package io.novafoundation.nova.feature_assets.domain.dashboard

import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_assets.data.model.PezkuwiDashboardData
import io.novafoundation.nova.feature_assets.data.repository.PezkuwiDashboardRepository
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry

class PezkuwiDashboardInteractor(
    private val repository: PezkuwiDashboardRepository,
    private val chainRegistry: ChainRegistry
) {

    suspend fun getDashboard(metaAccount: MetaAccount): Result<PezkuwiDashboardData> = runCatching {
        val peopleChain = chainRegistry.getChain(ChainGeneses.PEZKUWI_PEOPLE)
        val accountId = metaAccount.accountIdIn(peopleChain)
            ?: error("No account for People chain")

        repository.getDashboard(accountId)
    }
}
