package io.novafoundation.nova.feature_assets.domain.bridge.multisig

import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.data.network.runtime.binding.WeightV2
import io.novafoundation.nova.common.utils.Modules
import io.novafoundation.nova.common.utils.argumentType
import io.novafoundation.nova.common.utils.composeCall
import io.novafoundation.nova.feature_account_api.data.multisig.model.MultisigTimePoint
import io.novafoundation.nova.runtime.util.constructAccountLookupInstance
import io.novasama.substrate_sdk_android.runtime.RuntimeSnapshot
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import io.novasama.substrate_sdk_android.runtime.metadata.call
import java.math.BigInteger

/**
 * `Assets.approve_transfer(id, delegate, amount)` - field names verified against the actual
 * pallet_assets source this ecosystem runs (bizinikiwi/pezframe/assets/src/lib.rs), not guessed:
 * `pub fn approve_transfer(origin, id: T::AssetIdParameter, delegate: AccountIdLookupOf<T>,
 * #[compact] amount: T::Balance)`. `delegate` is a lookup/MultiAddress field, so it goes through
 * `constructAccountLookupInstance` - this chain uses non-standard numeric MultiAddress variant
 * names in places (see runtime/util/AccountLookup.kt's own comment), so hand-rolling this
 * wrapping instead of reusing the app's existing helper would be a real risk of a silently wrong
 * encoding.
 */
fun RuntimeSnapshot.composeAssetsApproveTransfer(
    assetId: Int,
    delegate: AccountIdKey,
    amount: BigInteger,
): GenericCall.Instance {
    val delegateType = metadata.module(Modules.ASSETS).call("approve_transfer").argumentType("delegate")

    return composeCall(
        moduleName = Modules.ASSETS,
        callName = "approve_transfer",
        arguments = mapOf(
            "id" to assetId.toBigInteger(),
            "delegate" to delegateType.constructAccountLookupInstance(delegate.value),
            "amount" to amount,
        )
    )
}

/**
 * `Multisig.as_multi(threshold, other_signatories, maybe_timepoint, call, max_weight)` - a plain
 * threshold/signatory-list version of feature-account-api's `composeMultisigAsMulti`, which
 * requires a full `MultisigMetaAccount` (only ever reads its `.threshold`/`.otherSignatories`,
 * but constructing one would mean either building a throwaway implementation of the whole
 * `MetaAccount` interface chain or going through this app's "add multisig wallet" import flow -
 * neither is needed here since this screen's multisig is fully known/hardcoded ahead of time).
 * Passing the full call (never just its hash) is always correct regardless of whether this
 * signature is the first, an intermediate, or the final approval - pallet_multisig's own
 * `operate()` only actually dispatches the call once threshold is reached with this vote,
 * otherwise it just records the approval, so there is no "hash-only" branch needed on this side.
 */
fun RuntimeSnapshot.composeBridgeMultisigAsMulti(
    threshold: Int,
    otherSignatories: List<AccountIdKey>,
    maybeTimePoint: MultisigTimePoint?,
    call: GenericCall.Instance,
    maxWeight: WeightV2,
): GenericCall.Instance {
    return composeCall(
        moduleName = Modules.MULTISIG,
        callName = "as_multi",
        arguments = mapOf(
            "threshold" to threshold.toBigInteger(),
            "other_signatories" to otherSignatories.map { it.value },
            "maybe_timepoint" to maybeTimePoint?.toEncodableInstance(),
            "call" to call,
            "max_weight" to maxWeight.toEncodableInstance(),
        )
    )
}
