package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.trc20

import io.novafoundation.nova.core.updater.SharedRequestsBuilder
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_wallet_api.data.cache.AssetCache
import io.novafoundation.nova.feature_wallet_api.data.cache.updateNonLockableAsset
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.AssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.BalanceSyncUpdate
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.ChainAssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.TransferableBalanceUpdatePoint
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.tronNative.pollingBalanceFlow
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi
import io.novafoundation.nova.runtime.ext.requireTronGridBaseUrl
import io.novafoundation.nova.runtime.ext.requireTrc20
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.AccountId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.math.BigInteger

/**
 * TRC-20 token balance on a Tron-based chain. Read-only (Phase 1): fetches via an on-chain `balanceOf` contract
 * call (see [TronGridApi.fetchTrc20Balance] for why this can't reuse the `/v1/accounts` endpoint that native
 * TRX balance reads from) and polls for updates. No transfer/history support here - see `TronAssetsModule`.
 */
class Trc20AssetBalance(
    private val assetCache: AssetCache,
    private val tronGridApi: TronGridApi,
) : AssetBalance {

    override suspend fun startSyncingBalanceLocks(
        metaAccount: MetaAccount,
        chain: Chain,
        chainAsset: Chain.Asset,
        accountId: AccountId,
        subscriptionBuilder: SharedRequestsBuilder
    ): Flow<*> {
        // TRC-20 tokens do not support locks
        return emptyFlow<Nothing>()
    }

    override fun isSelfSufficient(chainAsset: Chain.Asset): Boolean {
        return true
    }

    override suspend fun existentialDeposit(chainAsset: Chain.Asset): BigInteger {
        // TRC-20 tokens do not have an existential deposit concept
        return BigInteger.ZERO
    }

    override suspend fun queryAccountBalance(chain: Chain, chainAsset: Chain.Asset, accountId: AccountId): ChainAssetBalance {
        val contractAddress = chainAsset.requireTrc20().contractAddress

        val balance = tronGridApi.fetchTrc20Balance(chain.requireTronGridBaseUrl(), accountId, contractAddress)

        return ChainAssetBalance.fromFree(chainAsset, balance)
    }

    override suspend fun subscribeAccountBalanceUpdatePoint(
        chain: Chain,
        chainAsset: Chain.Asset,
        accountId: AccountId,
    ): Flow<TransferableBalanceUpdatePoint> {
        // Not on the critical sync path (mirrors EvmNativeAssetBalance) - out of scope for Phase 1 read-only support.
        TODO("Not yet implemented")
    }

    override suspend fun startSyncingBalance(
        chain: Chain,
        chainAsset: Chain.Asset,
        metaAccount: MetaAccount,
        accountId: AccountId,
        subscriptionBuilder: SharedRequestsBuilder
    ): Flow<BalanceSyncUpdate> {
        val contractAddress = chainAsset.requireTrc20().contractAddress
        val baseUrl = chain.requireTronGridBaseUrl()

        return pollingBalanceFlow { tronGridApi.fetchTrc20Balance(baseUrl, accountId, contractAddress) }
            .map { balance ->
                assetCache.updateNonLockableAsset(metaAccount.id, chainAsset, balance)

                BalanceSyncUpdate.NoCause
            }
    }
}
