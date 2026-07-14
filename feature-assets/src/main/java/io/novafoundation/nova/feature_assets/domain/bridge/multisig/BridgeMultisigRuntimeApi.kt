package io.novafoundation.nova.feature_assets.domain.bridge.multisig

import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.data.network.runtime.binding.bindAccountIdKey
import io.novafoundation.nova.common.data.network.runtime.binding.bindList
import io.novafoundation.nova.common.data.network.runtime.binding.bindNumber
import io.novafoundation.nova.common.data.network.runtime.binding.castToStruct
import io.novafoundation.nova.common.utils.Modules
import io.novafoundation.nova.feature_account_api.data.multisig.model.MultisigTimePoint
import io.novafoundation.nova.feature_account_api.domain.multisig.CallHash
import io.novafoundation.nova.runtime.storage.source.query.StorageQueryContext
import io.novafoundation.nova.runtime.storage.source.query.api.QueryableModule
import io.novafoundation.nova.runtime.storage.source.query.api.QueryableStorageEntry2
import io.novafoundation.nova.runtime.storage.source.query.api.QueryableStorageEntry3
import io.novafoundation.nova.runtime.storage.source.query.api.converters.scaleDecoder
import io.novafoundation.nova.runtime.storage.source.query.api.converters.scaleEncoder
import io.novafoundation.nova.runtime.storage.source.query.api.storage2
import io.novafoundation.nova.runtime.storage.source.query.api.storage3
import io.novasama.substrate_sdk_android.runtime.metadata.RuntimeMetadata
import io.novasama.substrate_sdk_android.runtime.metadata.module
import io.novasama.substrate_sdk_android.runtime.metadata.module.Module
import java.math.BigInteger

/**
 * Minimal, Bridge-screen-local mirrors of the equivalents already used by the multisig-operations
 * feature (feature-account-impl's `MultisigRuntimeApi`/`OnChainMultisig`) - duplicated here rather
 * than imported because feature-assets depends on feature-account-api, not feature-account-impl
 * (standard module-boundary convention in this app: features don't reach into each other's impl
 * modules). Only what this screen actually needs is modeled.
 */

@JvmInline
value class BridgeAssetsApi(override val module: Module) : QueryableModule

context(StorageQueryContext)
fun RuntimeMetadata.bridgeAssets(): BridgeAssetsApi = BridgeAssetsApi(module(Modules.ASSETS))

/** `Assets.Approvals(asset_id, owner, delegate) -> Approval { amount, deposit }` - only `amount`
 *  (the remaining spendable allowance) is needed here. */
context(StorageQueryContext)
val BridgeAssetsApi.approvalAmount: QueryableStorageEntry3<BigInteger, AccountIdKey, AccountIdKey, BigInteger>
    get() = storage3(
        name = "Approvals",
        binding = { decoded, _, _, _ -> bindNumber(decoded.castToStruct()["amount"]) },
        key2ToInternalConverter = AccountIdKey.scaleEncoder,
        key3ToInternalConverter = AccountIdKey.scaleEncoder,
        key2FromInternalConverter = AccountIdKey.scaleDecoder,
        key3FromInternalConverter = AccountIdKey.scaleDecoder,
    )

@JvmInline
value class BridgeMultisigApi(override val module: Module) : QueryableModule

context(StorageQueryContext)
fun RuntimeMetadata.bridgeMultisig(): BridgeMultisigApi = BridgeMultisigApi(module(Modules.MULTISIG))

class BridgeOnChainMultisig(
    val approvals: List<AccountIdKey>,
    val timePoint: MultisigTimePoint,
)

/** `Multisig.Multisigs(multisig_account, call_hash) -> Multisig { approvals, when, ... }` -
 *  mirrors `OnChainMultisig` but only carries what this screen needs (not deposit/depositor). */
context(StorageQueryContext)
val BridgeMultisigApi.multisigs: QueryableStorageEntry2<AccountIdKey, CallHash, BridgeOnChainMultisig>
    get() = storage2(
        name = "Multisigs",
        binding = { decoded, _, _ ->
            val struct = decoded.castToStruct()
            BridgeOnChainMultisig(
                approvals = bindList(struct["approvals"], ::bindAccountIdKey),
                timePoint = MultisigTimePoint.bind(struct["when"]),
            )
        },
        key1ToInternalConverter = AccountIdKey.scaleEncoder,
        key1FromInternalConverter = AccountIdKey.scaleDecoder,
        key2ToInternalConverter = CallHash.scaleEncoder,
        key2FromInternalConverter = CallHash.scaleDecoder,
    )
