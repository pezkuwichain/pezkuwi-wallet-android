package io.novafoundation.nova.feature_account_api.data.multisig.model

import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.address.toHex
import io.novafoundation.nova.common.utils.Identifiable
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.MultisigMetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.addressIn
import io.novafoundation.nova.feature_account_api.domain.multisig.CallHash
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import java.math.BigInteger
import kotlin.time.Duration

class PendingMultisigOperation(
    val multisigMetaId: Long,
    val call: GenericCall.Instance?,
    val callHash: CallHash,
    val chain: Chain,
    /**
     * Null means this call has never been submitted on-chain yet (no `Multisig.Multisigs` entry
     * exists) - the "first signer" case reachable via a `/open/multisigOperation` deep link
     * before anyone has signed. `composeMultisigAsMulti`'s `maybeTimePoint` parameter already
     * accepts null for exactly this (see `MultisigSigner.wrapCallsInAsMulti`, which uses the
     * same null-timepoint-for-first-submission pattern for ordinary multisig-origin calls) -
     * this model just didn't have a way to represent that state before.
     */
    val timePoint: MultisigTimePoint?,
    val approvals: List<AccountIdKey>,
    /**
     * Null for the same not-yet-submitted case as [timePoint] - nobody has deposited/proposed
     * this call yet, so there is no depositor. [userAction] already handles this correctly:
     * comparing [signatoryAccountId] to a null depositor is simply never true, so a signatory
     * viewing a not-yet-submitted call is never offered "Reject" (rejecting something that was
     * never proposed makes no sense - only cancel_as_multi on an *existing* operation does).
     */
    val depositor: AccountIdKey?,
    val deposit: BigInteger,
    val signatoryAccountId: AccountIdKey,
    val signatoryMetaId: Long,
    val threshold: Int,
    val timestamp: Duration,
) : Identifiable {

    val operationId = PendingMultisigOperationId(multisigMetaId, chain.id, callHash.toHex())

    val isSubmittedOnChain: Boolean
        get() = timePoint != null

    override val identifier: String = operationId.identifier()

    override fun toString(): String {
        val callFormatted = if (call != null) {
            "${call.module.name}.${call.function.name}"
        } else {
            callHash.toHex()
        }

        return "Call: $callFormatted, Chain: ${chain.name}, Approvals: ${approvals.size}/$threshold, User action: ${userAction()}"
    }

    companion object
}

data class PendingMultisigOperationId(
    val metaId: Long,
    val chainId: ChainId,
    val callHash: String,
) {
    companion object;
}

fun PendingMultisigOperation.userAction(): MultisigAction {
    return when (signatoryAccountId) {
        depositor -> MultisigAction.CanReject
        !in approvals -> MultisigAction.CanApprove(
            isFinalApproval = approvals.size == threshold - 1
        )

        else -> MultisigAction.Signed
    }
}

fun PendingMultisigOperationId.identifier() = toString()

/**
 * operation hash is based on address in chain and ignored meta account id
 */
fun PendingMultisigOperation.Companion.createOperationHash(metaAccount: MetaAccount, chain: Chain, callHash: String): String {
    return "${metaAccount.addressIn(chain)}:${chain.id}:$callHash"
        .toByteArray()
        .toHexString(withPrefix = true)
}

fun PendingMultisigOperationId.Companion.create(metaAccount: MetaAccount, chain: Chain, callHash: String): PendingMultisigOperationId {
    return PendingMultisigOperationId(metaAccount.id, chain.id, callHash)
}

/**
 * Builds a synthetic [PendingMultisigOperation] for a call that has never been submitted
 * on-chain - the deep-link "first signer" case (see `MultisigOperationDetailsDeepLinkHandler`
 * and `RealMultisigOperationDetailsInteractor.buildNotYetSubmittedOperation`). Unlike
 * `PendingMultisigOperation.from` (used by the chain-storage-driven syncer), this never touches
 * chain state - everything it needs (threshold, other signatories) is already known locally
 * from the already-added [MultisigMetaAccount], and [call] comes from the deep link's `callData`
 * param, whose hash the caller must already have verified matches the link's `callHash`.
 */
fun PendingMultisigOperation.Companion.notYetSubmitted(
    multisigMetaAccount: MultisigMetaAccount,
    call: GenericCall.Instance,
    callHash: CallHash,
    chain: Chain,
    timestamp: Duration,
): PendingMultisigOperation {
    return PendingMultisigOperation(
        multisigMetaId = multisigMetaAccount.id,
        call = call,
        callHash = callHash,
        chain = chain,
        timePoint = null,
        approvals = emptyList(),
        depositor = null,
        deposit = BigInteger.ZERO,
        signatoryAccountId = multisigMetaAccount.signatoryAccountId,
        signatoryMetaId = multisigMetaAccount.signatoryMetaId,
        threshold = multisigMetaAccount.threshold,
        timestamp = timestamp,
    )
}
