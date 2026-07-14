package io.novafoundation.nova.feature_assets.domain.bridge.multisig

import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.address.intoKey
import io.novafoundation.nova.common.address.toHexWithPrefix
import io.novafoundation.nova.common.data.network.runtime.binding.WeightV2
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.common.utils.callHash
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicService
import io.novafoundation.nova.feature_account_api.data.extrinsic.execution.ExtrinsicExecutionResult
import io.novafoundation.nova.feature_account_api.data.extrinsic.execution.requireOk
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_account_api.domain.multisig.intoCallHash
import io.novafoundation.nova.runtime.di.REMOTE_STORAGE_SOURCE
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Named

data class BridgeSignerState(
    val signatoryRole: String,
    val remainingAllowance: BigInteger,
    val needsRenewal: Boolean,
    val alreadySignedPendingRenewal: Boolean,
    val approvalsSoFar: Int,
)

interface BridgeMultisigInteractor {

    /** Null if the currently selected wallet isn't one of the 5 known bridge signatories - the
     *  Bridge screen shows nothing in that case, this feature doesn't exist for anyone else. */
    suspend fun getSignerState(): BridgeSignerState?

    suspend fun submitRenewalSignature(): Result<ExtrinsicExecutionResult>
}

@FeatureScope
class RealBridgeMultisigInteractor @Inject constructor(
    private val chainRegistry: ChainRegistry,
    private val selectedAccountUseCase: SelectedAccountUseCase,
    @Named(REMOTE_STORAGE_SOURCE) private val storageDataSource: StorageDataSource,
    private val extrinsicService: ExtrinsicService,
) : BridgeMultisigInteractor {

    override suspend fun getSignerState(): BridgeSignerState? {
        val chain = chainRegistry.getChain(ChainGeneses.PEZKUWI_ASSET_HUB)
        val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
        val myAccountId = metaAccount.accountIdIn(chain)?.intoKey() ?: return null

        val signatory = BridgeMultisigConstants.SIGNATORIES.firstOrNull {
            it.address.toAccountId().intoKey() == myAccountId
        } ?: return null

        val remaining = queryRemainingAllowance(chain)
        val needsRenewal = remaining < BigInteger.valueOf(BridgeMultisigConstants.RENEWAL_THRESHOLD)

        var alreadySigned = false
        var approvalsSoFar = 0
        if (needsRenewal) {
            val pending = queryPendingRenewal(chain)
            if (pending != null) {
                approvalsSoFar = pending.approvals.size
                alreadySigned = pending.approvals.contains(myAccountId)
            }
        }

        return BridgeSignerState(
            signatoryRole = signatory.role,
            remainingAllowance = remaining,
            needsRenewal = needsRenewal,
            alreadySignedPendingRenewal = alreadySigned,
            approvalsSoFar = approvalsSoFar,
        )
    }

    override suspend fun submitRenewalSignature(): Result<ExtrinsicExecutionResult> = runCatching {
        val chain = chainRegistry.getChain(ChainGeneses.PEZKUWI_ASSET_HUB)
        val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
        val myAccountId = requireNotNull(metaAccount.accountIdIn(chain)?.intoKey()) {
            "Selected account has no address on Pezkuwi Asset Hub"
        }

        val otherSignatories = BridgeMultisigConstants.SIGNATORIES
            .map { it.address.toAccountId().intoKey() }
            .filter { it != myAccountId }
            .sortedBy { it.toHexWithPrefix() }

        val pending = queryPendingRenewal(chain)
        check(pending == null || !pending.approvals.contains(myAccountId)) {
            "Already signed this renewal - waiting for other signers"
        }

        extrinsicService.submitExtrinsicAndAwaitExecution(
            chain = chain,
            origin = TransactionOrigin.WalletWithId(metaAccount.id)
        ) {
            val approveTransferCall = runtime.composeAssetsApproveTransfer(
                assetId = BridgeMultisigConstants.WUSDT_ASSET_ID,
                delegate = BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS.toAccountId().intoKey(),
                amount = BigInteger.valueOf(BridgeMultisigConstants.TOPUP_AMOUNT),
            )

            val multisigCall = runtime.composeBridgeMultisigAsMulti(
                threshold = BridgeMultisigConstants.THRESHOLD,
                otherSignatories = otherSignatories,
                maybeTimePoint = pending?.timePoint,
                call = approveTransferCall,
                maxWeight = WeightV2(BigInteger.valueOf(1_000_000_000L), BigInteger.valueOf(200_000L)),
            )

            call(multisigCall)
        }.getOrThrow().requireOk()
    }

    private suspend fun queryRemainingAllowance(chain: Chain): BigInteger {
        val multisigAccountId = BridgeMultisigConstants.MULTISIG_ADDRESS.toAccountId().intoKey()
        val delegateAccountId = BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS.toAccountId().intoKey()

        return storageDataSource.query(chain.id) {
            runtime.metadata.bridgeAssets().approvalAmount.query(
                BridgeMultisigConstants.WUSDT_ASSET_ID.toBigInteger(),
                multisigAccountId,
                delegateAccountId,
            )
        } ?: BigInteger.ZERO
    }

    private suspend fun queryPendingRenewal(chain: Chain): BridgeOnChainMultisig? {
        val multisigAccountId = BridgeMultisigConstants.MULTISIG_ADDRESS.toAccountId().intoKey()
        val callHash = renewalCallHash(chain)

        return storageDataSource.query(chain.id) {
            runtime.metadata.bridgeMultisig().multisigs.query(multisigAccountId, callHash)
        }
    }

    private suspend fun renewalCallHash(chain: Chain): AccountIdKey {
        val runtime = chainRegistry.getRuntime(chain.id)
        val call = runtime.composeAssetsApproveTransfer(
            assetId = BridgeMultisigConstants.WUSDT_ASSET_ID,
            delegate = BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS.toAccountId().intoKey(),
            amount = BigInteger.valueOf(BridgeMultisigConstants.TOPUP_AMOUNT),
        )
        return call.callHash(runtime).intoCallHash()
    }
}
