package io.novafoundation.nova.feature_assets.presentation.bridge

import io.novafoundation.nova.feature_account_api.data.multisig.model.PendingMultisigOperationId
import io.novafoundation.nova.feature_account_api.presenatation.chain.ChainUi

data class PendingSignatureModel(
    val id: PendingMultisigOperationId,
    val chain: ChainUi,
    val title: String,
    val subtitle: String?,
    val primaryValue: CharSequence?,
    val progress: String,
)
