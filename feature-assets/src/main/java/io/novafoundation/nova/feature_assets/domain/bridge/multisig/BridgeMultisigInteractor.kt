package io.novafoundation.nova.feature_assets.domain.bridge.multisig

import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.address.fromHexOrNull
import io.novafoundation.nova.common.address.intoKey
import io.novafoundation.nova.common.address.toHexWithPrefix
import io.novafoundation.nova.common.data.config.GlobalConfigDataSource
import io.novafoundation.nova.common.data.network.runtime.binding.WeightV2
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.common.utils.callHash
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicService
import io.novafoundation.nova.feature_account_api.data.extrinsic.execution.ExtrinsicExecutionResult
import io.novafoundation.nova.feature_account_api.data.extrinsic.execution.requireOk
import io.novafoundation.nova.feature_account_api.data.multisig.model.MultisigTimePoint
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_api.domain.multisig.CallHash
import io.novafoundation.nova.feature_account_api.domain.multisig.intoCallHash
import io.novafoundation.nova.runtime.di.REMOTE_STORAGE_SOURCE
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import io.novasama.substrate_sdk_android.runtime.definitions.types.fromHexOrNull
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
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

/** A pending Multisig.as_multi call against one of the bridge's own multisig accounts (a real
 *  user swap someone else submitted - NOT this wallet's own renewal signature, see
 *  BridgeSignerState for that), that this signatory hasn't approved yet. [call] is null when the
 *  off-chain indexer hasn't got the call content for this hash yet (or lookup failed) - callers
 *  must treat that as "cannot show/sign this one", never fall back to hash-only approval. */
data class PendingBridgeApproval(
    val chain: Chain,
    val callHash: CallHash,
    val timePoint: MultisigTimePoint,
    val approvalsCount: Int,
    val call: GenericCall.Instance?,
)

interface BridgeMultisigInteractor {

    /** Null if the currently selected wallet isn't one of the 5 known bridge signatories - the
     *  Bridge screen shows nothing in that case, this feature doesn't exist for anyone else. */
    suspend fun getSignerState(): BridgeSignerState?

    suspend fun submitRenewalSignature(): Result<ExtrinsicExecutionResult>

    /** Same as getSignerState/submitRenewalSignature but for the automation key's real-USDT
     *  spending approval on Polkadot Asset Hub - the leg that gates wUSDT->USDT withdrawal
     *  auto-pay. Confirmed on-chain (2026-07-16) this approval has never been granted at all, so
     *  needsRenewal is currently always true for every signatory until the first renewal signs. */
    suspend fun getPolkadotSignerState(): BridgeSignerState?

    suspend fun submitPolkadotRenewalSignature(): Result<ExtrinsicExecutionResult>

    /** Real USDT (base units) the multisig actually holds on Polkadot Asset Hub right now - the
     *  true backing for wUSDT->USDT withdrawals. Replaces the old wusdtToUsdtActive boolean
     *  fetched from the legacy bridge bot's :3030/status endpoint, which this session stopped
     *  (see res/validators-tiki.md) - that made the old check always report "inactive"
     *  regardless of real reserve. A specific withdrawal should be allowed whenever it's covered
     *  by this real balance, not gated on an unrelated dead service or on total supply parity. */
    suspend fun getPolkadotUsdtReserve(): BigInteger

    /** Real remaining amount (base units) the automation key is currently approved to auto-pay
     *  out of the multisig's own wUSDT on Pezkuwi Asset Hub - the deterministic on-chain fact
     *  that decides whether a USDT->wUSDT deposit CAN possibly auto-pay (bounded further by the
     *  backend's own daily cap, which isn't visible from the wallet - this is a necessary, not
     *  sufficient, condition for auto-pay). Available to any wallet, not just signatories, since
     *  it drives the Bridge screen's pre-submit consent gate for everyone. */
    suspend fun getWusdtRemainingAllowance(): BigInteger

    /** Same as getWusdtRemainingAllowance but for the automation key's real USDT approval on
     *  Polkadot Asset Hub - gates wUSDT->USDT withdrawal auto-pay. */
    suspend fun getPolkadotUsdtRemainingAllowance(): BigInteger

    /** Every pending swap-approval call (on either chain) against the bridge's own multisig
     *  accounts that this signatory hasn't approved yet - empty if the selected wallet isn't one
     *  of the 5 known signatories. Unlike the app's generic multisig-operations feature (which
     *  only tracks accounts formally added as a `MultisigMetaAccount`), this queries the bridge's
     *  hardcoded multisig addresses directly, since individual signatories use their own regular
     *  wallet to sign here - they never "are" the multisig account itself. */
    suspend fun getPendingApprovals(): List<PendingBridgeApproval>

    /** Approves (contributes this wallet's signature to) an existing pending call. Refuses to
     *  proceed if [PendingBridgeApproval.call] is null - approving a call whose content this
     *  device can't verify would be blind-signing, never acceptable for a multisig approval. */
    suspend fun submitApproval(approval: PendingBridgeApproval): Result<ExtrinsicExecutionResult>
}

@FeatureScope
class RealBridgeMultisigInteractor @Inject constructor(
    private val chainRegistry: ChainRegistry,
    private val selectedAccountUseCase: SelectedAccountUseCase,
    @Named(REMOTE_STORAGE_SOURCE) private val storageDataSource: StorageDataSource,
    private val extrinsicService: ExtrinsicService,
    private val bridgeMultisigOperationsApi: BridgeMultisigOperationsApi,
    private val globalConfigDataSource: GlobalConfigDataSource,
) : BridgeMultisigInteractor {

    override suspend fun getSignerState(): BridgeSignerState? {
        val chain = chainRegistry.getChain(ChainGeneses.PEZKUWI_ASSET_HUB)
        return getSignerStateFor(
            chain = chain,
            assetId = BridgeMultisigConstants.WUSDT_ASSET_ID,
            automationKeyAddress = BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS,
            renewalThreshold = BridgeMultisigConstants.RENEWAL_THRESHOLD,
        )
    }

    override suspend fun submitRenewalSignature(): Result<ExtrinsicExecutionResult> = submitRenewalSignatureFor(
        chain = chainRegistry.getChain(ChainGeneses.PEZKUWI_ASSET_HUB),
        assetId = BridgeMultisigConstants.WUSDT_ASSET_ID,
        automationKeyAddress = BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS,
        topupAmount = BridgeMultisigConstants.TOPUP_AMOUNT,
    )

    override suspend fun getPolkadotSignerState(): BridgeSignerState? {
        val chain = chainRegistry.getChain(ChainGeneses.POLKADOT_ASSET_HUB)
        return getSignerStateFor(
            chain = chain,
            assetId = BridgeMultisigConstants.POLKADOT_USDT_ASSET_ID,
            automationKeyAddress = BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS_POLKADOT,
            renewalThreshold = BridgeMultisigConstants.POLKADOT_RENEWAL_THRESHOLD,
        )
    }

    override suspend fun submitPolkadotRenewalSignature(): Result<ExtrinsicExecutionResult> = submitRenewalSignatureFor(
        chain = chainRegistry.getChain(ChainGeneses.POLKADOT_ASSET_HUB),
        assetId = BridgeMultisigConstants.POLKADOT_USDT_ASSET_ID,
        automationKeyAddress = BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS_POLKADOT,
        topupAmount = BridgeMultisigConstants.POLKADOT_TOPUP_AMOUNT,
    )

    override suspend fun getPolkadotUsdtReserve(): BigInteger {
        val polkadotChain = chainRegistry.getChain(ChainGeneses.POLKADOT_ASSET_HUB)
        val multisigAccountId = BridgeMultisigConstants.MULTISIG_ADDRESS_POLKADOT.toAccountId().intoKey()

        return storageDataSource.query(polkadotChain.id) {
            runtime.metadata.bridgeAssets().assetBalance.query(
                BridgeMultisigConstants.POLKADOT_USDT_ASSET_ID.toBigInteger(),
                multisigAccountId,
            )
        } ?: BigInteger.ZERO
    }

    override suspend fun getWusdtRemainingAllowance(): BigInteger {
        val chain = chainRegistry.getChain(ChainGeneses.PEZKUWI_ASSET_HUB)
        return queryRemainingAllowance(chain, BridgeMultisigConstants.WUSDT_ASSET_ID, BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS)
    }

    override suspend fun getPolkadotUsdtRemainingAllowance(): BigInteger {
        val chain = chainRegistry.getChain(ChainGeneses.POLKADOT_ASSET_HUB)
        return queryRemainingAllowance(chain, BridgeMultisigConstants.POLKADOT_USDT_ASSET_ID, BridgeMultisigConstants.AUTOMATION_KEY_ADDRESS_POLKADOT)
    }

    override suspend fun getPendingApprovals(): List<PendingBridgeApproval> {
        val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()

        return listOf(
            ChainGeneses.PEZKUWI_ASSET_HUB to BridgeMultisigConstants.MULTISIG_ADDRESS,
            ChainGeneses.POLKADOT_ASSET_HUB to BridgeMultisigConstants.MULTISIG_ADDRESS_POLKADOT,
        ).flatMap { (chainGenesis, multisigAddress) ->
            runCatching { getPendingApprovalsFor(chainGenesis, multisigAddress, metaAccount) }.getOrElse { emptyList() }
        }
    }

    private suspend fun getPendingApprovalsFor(
        chainGenesis: String,
        multisigAddress: String,
        metaAccount: MetaAccount,
    ): List<PendingBridgeApproval> {
        val chain = chainRegistry.getChain(chainGenesis)
        val myAccountId = metaAccount.accountIdIn(chain)?.intoKey() ?: return emptyList()

        // Only the 5 known bridge signatories can ever have anything to approve here - same gate
        // as getSignerStateFor, matching how the rest of this screen already scopes itself.
        val isKnownSignatory = BridgeMultisigConstants.SIGNATORIES.any { it.address.toAccountId().intoKey() == myAccountId }
        if (!isKnownSignatory) return emptyList()

        val multisigAccountId = multisigAddress.toAccountId().intoKey()

        val keys = storageDataSource.query(chain.id) {
            runtime.metadata.bridgeMultisig().multisigs.keys(multisigAccountId)
        }
        if (keys.isEmpty()) return emptyList()

        val entries = storageDataSource.query(chain.id) {
            runtime.metadata.bridgeMultisig().multisigs.entries(keys)
        }

        val notYetApprovedByMe = entries.filterNot { (_, onChainMultisig) -> myAccountId in onChainMultisig.approvals }
        if (notYetApprovedByMe.isEmpty()) return emptyList()

        val callHashes = notYetApprovedByMe.keys.map { it.second }
        val callDataByHash = fetchCallData(chain, multisigAccountId, callHashes)

        return notYetApprovedByMe.map { (key, onChainMultisig) ->
            val callHash = key.second
            PendingBridgeApproval(
                chain = chain,
                callHash = callHash,
                timePoint = onChainMultisig.timePoint,
                approvalsCount = onChainMultisig.approvals.size,
                call = callDataByHash[callHash],
            )
        }
    }

    private suspend fun fetchCallData(
        chain: Chain,
        multisigAccountId: AccountIdKey,
        callHashes: List<CallHash>,
    ): Map<CallHash, GenericCall.Instance?> {
        return runCatching {
            val globalConfig = globalConfigDataSource.getGlobalConfig()
            val request = BridgeOffChainCallDataRequest(multisigAccountId, callHashes, chain.id)
            val response = bridgeMultisigOperationsApi.getCallDatas(globalConfig.multisigsApiUrl, request)
            val runtime = chainRegistry.getRuntime(chain.id)

            response.data.multisigOperations.nodes.mapNotNull { node ->
                val hash = CallHash.fromHexOrNull(node.callHash) ?: return@mapNotNull null
                val call = node.callData?.let { GenericCall.fromHexOrNull(runtime, it) }
                hash to call
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    override suspend fun submitApproval(approval: PendingBridgeApproval): Result<ExtrinsicExecutionResult> = runCatching {
        val call = requireNotNull(approval.call) {
            "Cannot approve a call whose content is unknown - refusing to blind-sign"
        }

        val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
        val myAccountId = requireNotNull(metaAccount.accountIdIn(approval.chain)?.intoKey()) {
            "Selected account has no address on ${approval.chain.name}"
        }

        val otherSignatories = BridgeMultisigConstants.SIGNATORIES
            .map { it.address.toAccountId().intoKey() }
            .filter { it != myAccountId }
            .sortedBy { it.toHexWithPrefix() }

        extrinsicService.submitExtrinsicAndAwaitExecution(
            chain = approval.chain,
            origin = TransactionOrigin.WalletWithId(metaAccount.id)
        ) {
            val multisigCall = runtime.composeBridgeMultisigAsMulti(
                threshold = BridgeMultisigConstants.THRESHOLD,
                otherSignatories = otherSignatories,
                maybeTimePoint = approval.timePoint,
                call = call,
                maxWeight = WeightV2(BigInteger.valueOf(1_000_000_000L), BigInteger.valueOf(200_000L)),
            )

            call(multisigCall)
        }.getOrThrow().requireOk()
    }

    /** Shared by both legs - the only differences between the wUSDT (Pezkuwi) and USDT (Polkadot)
     *  renewal flows are which chain/asset/automation-key-address/threshold to use, the actual
     *  on-chain call shape (Assets.approve_transfer wrapped in Multisig.as_multi) is identical. */
    private suspend fun getSignerStateFor(
        chain: Chain,
        assetId: Int,
        automationKeyAddress: String,
        renewalThreshold: Long,
    ): BridgeSignerState? {
        val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
        val myAccountId = metaAccount.accountIdIn(chain)?.intoKey() ?: return null

        val signatory = BridgeMultisigConstants.SIGNATORIES.firstOrNull {
            it.address.toAccountId().intoKey() == myAccountId
        } ?: return null

        val remaining = queryRemainingAllowance(chain, assetId, automationKeyAddress)
        val needsRenewal = remaining < BigInteger.valueOf(renewalThreshold)

        var alreadySigned = false
        var approvalsSoFar = 0
        if (needsRenewal) {
            val pending = queryPendingRenewal(chain, assetId, automationKeyAddress)
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

    private suspend fun submitRenewalSignatureFor(
        chain: Chain,
        assetId: Int,
        automationKeyAddress: String,
        topupAmount: Long,
    ): Result<ExtrinsicExecutionResult> = runCatching {
        val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
        val myAccountId = requireNotNull(metaAccount.accountIdIn(chain)?.intoKey()) {
            "Selected account has no address on ${chain.name}"
        }

        val otherSignatories = BridgeMultisigConstants.SIGNATORIES
            .map { it.address.toAccountId().intoKey() }
            .filter { it != myAccountId }
            .sortedBy { it.toHexWithPrefix() }

        val pending = queryPendingRenewal(chain, assetId, automationKeyAddress)
        check(pending == null || !pending.approvals.contains(myAccountId)) {
            "Already signed this renewal - waiting for other signers"
        }

        extrinsicService.submitExtrinsicAndAwaitExecution(
            chain = chain,
            origin = TransactionOrigin.WalletWithId(metaAccount.id)
        ) {
            val approveTransferCall = runtime.composeAssetsApproveTransfer(
                assetId = assetId,
                delegate = automationKeyAddress.toAccountId().intoKey(),
                amount = BigInteger.valueOf(topupAmount),
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

    private suspend fun queryRemainingAllowance(chain: Chain, assetId: Int, automationKeyAddress: String): BigInteger {
        val multisigAccountId = multisigAddressFor(chain).toAccountId().intoKey()
        val delegateAccountId = automationKeyAddress.toAccountId().intoKey()

        return storageDataSource.query(chain.id) {
            runtime.metadata.bridgeAssets().approvalAmount.query(
                assetId.toBigInteger(),
                multisigAccountId,
                delegateAccountId,
            )
        } ?: BigInteger.ZERO
    }

    private suspend fun queryPendingRenewal(chain: Chain, assetId: Int, automationKeyAddress: String): BridgeOnChainMultisig? {
        val multisigAccountId = multisigAddressFor(chain).toAccountId().intoKey()
        val callHash = renewalCallHash(chain, assetId, automationKeyAddress)

        return storageDataSource.query(chain.id) {
            runtime.metadata.bridgeMultisig().multisigs.query(multisigAccountId, callHash)
        }
    }

    private suspend fun renewalCallHash(chain: Chain, assetId: Int, automationKeyAddress: String): AccountIdKey {
        val topupAmount = if (chain.id == ChainGeneses.POLKADOT_ASSET_HUB) {
            BridgeMultisigConstants.POLKADOT_TOPUP_AMOUNT
        } else {
            BridgeMultisigConstants.TOPUP_AMOUNT
        }

        val runtime = chainRegistry.getRuntime(chain.id)
        val call = runtime.composeAssetsApproveTransfer(
            assetId = assetId,
            delegate = automationKeyAddress.toAccountId().intoKey(),
            amount = BigInteger.valueOf(topupAmount),
        )
        return call.callHash(runtime).intoCallHash()
    }

    private fun multisigAddressFor(chain: Chain): String {
        return if (chain.id == ChainGeneses.POLKADOT_ASSET_HUB) {
            BridgeMultisigConstants.MULTISIG_ADDRESS_POLKADOT
        } else {
            BridgeMultisigConstants.MULTISIG_ADDRESS
        }
    }
}
