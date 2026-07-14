package io.novafoundation.nova.feature_multisig_operations.presentation.common

import android.os.Parcelable
import io.novafoundation.nova.feature_account_api.data.multisig.model.PendingMultisigOperationId
import kotlinx.parcelize.Parcelize

@Parcelize
class MultisigOperationPayload(
    val chainId: String,
    val metaId: Long,
    val callHash: String,
    /**
     * Call data for a call that may not exist on-chain yet (the "first signer" deep-link case -
     * see `MultisigOperationDetailsDeepLinkHandler`). Null for the normal case where the details
     * screen sources everything from the chain-storage-driven sync service.
     */
    val notSubmittedCallData: String? = null,
) : Parcelable {
    companion object;
}

fun MultisigOperationPayload.Companion.fromOperationId(
    operationId: PendingMultisigOperationId,
    notSubmittedCallData: String? = null,
): MultisigOperationPayload {
    return MultisigOperationPayload(
        chainId = operationId.chainId,
        metaId = operationId.metaId,
        callHash = operationId.callHash,
        notSubmittedCallData = notSubmittedCallData,
    )
}

fun MultisigOperationPayload.toOperationId(): PendingMultisigOperationId {
    return PendingMultisigOperationId(
        chainId = chainId,
        metaId = metaId,
        callHash = callHash
    )
}
