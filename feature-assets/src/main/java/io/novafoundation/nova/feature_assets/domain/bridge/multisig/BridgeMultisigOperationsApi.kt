package io.novafoundation.nova.feature_assets.domain.bridge.multisig

import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.address.toHexWithPrefix
import io.novafoundation.nova.common.data.network.subquery.SubQueryFilters
import io.novafoundation.nova.common.data.network.subquery.SubQueryNodes
import io.novafoundation.nova.common.data.network.subquery.SubQueryResponse
import io.novafoundation.nova.common.utils.HexString
import io.novafoundation.nova.feature_account_api.domain.multisig.CallHash
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novasama.substrate_sdk_android.extensions.requireHexPrefix
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Minimal, Bridge-screen-local mirror of feature-account-impl's `FindMultisigsApi.getCallDatas`/
 * `OffChainPendingMultisigInfoRequest`/`GetPedingMultisigOperationsResponse` - same module-
 * boundary reason as BridgeMultisigRuntimeApi.kt (feature-assets can't depend on
 * feature-account-impl). Only `getCallDatas` is mirrored - `findMultisigs` isn't needed here
 * since the bridge's multisig account is already known (BridgeMultisigConstants), not discovered.
 */
interface BridgeMultisigOperationsApi {

    @POST
    suspend fun getCallDatas(
        @Url url: String,
        @Body body: BridgeOffChainCallDataRequest
    ): SubQueryResponse<BridgeCallDataResponse>
}

class BridgeOffChainCallDataRequest(
    accountIdKey: AccountIdKey,
    callHashes: Collection<CallHash>,
    chainId: ChainId
) : SubQueryFilters {

    @Transient
    private val callHashesHex = callHashes.map { it.toHexWithPrefix() }

    val query = """
        query {
          multisigOperations(filter:  {
             ${"accountId" equalTo accountIdKey.toHexWithPrefix() }
             ${"status" equalToEnum "pending"}
             ${"callHash" presentIn callHashesHex}
             ${"chainId" equalTo chainId.requireHexPrefix()}
          }) {
            nodes {
              callHash
              callData
            }
          }
        }
    """.trimIndent()
}

class BridgeCallDataResponse(val multisigOperations: SubQueryNodes<BridgeOperationRemote>) {

    class BridgeOperationRemote(val callHash: HexString, val callData: HexString?)
}
