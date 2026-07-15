package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.tronNative

import io.novafoundation.nova.core.updater.SharedRequestsBuilder
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_wallet_api.data.cache.AssetCache
import io.novafoundation.nova.feature_wallet_api.data.cache.updateNonLockableAsset
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.AssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.BalanceSyncUpdate
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.ChainAssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.TransferableBalanceUpdatePoint
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi
import io.novafoundation.nova.runtime.ext.addressOf
import io.novafoundation.nova.runtime.ext.requireTronGridBaseUrl
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.AccountId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.math.BigInteger

/**
 * Native TRX balance on a Tron-based chain. Read-only (Phase 1): fetches via TronGrid's REST API and polls for
 * updates, since TronGrid has no push/subscription mechanism. No transfer/history support here - see
 * `TronAssetsModule` for how this is paired with `UnsupportedAssetTransfers`/`UnsupportedAssetHistory`.
 */
class TronNativeAssetBalance(
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
        // Tron native balance does not support locks
        return emptyFlow<Nothing>()
    }

    override fun isSelfSufficient(chainAsset: Chain.Asset): Boolean {
        return true
    }

    override suspend fun existentialDeposit(chainAsset: Chain.Asset): BigInteger {
        // Tron does not have an existential deposit concept
        return BigInteger.ZERO
    }

    override suspend fun queryAccountBalance(chain: Chain, chainAsset: Chain.Asset, accountId: AccountId): ChainAssetBalance {
        val balance = tronGridApi.fetchNativeBalance(chain.requireTronGridBaseUrl(), chain.addressOf(accountId))

        return ChainAssetBalance.fromFree(chainAsset, balance)
    }

    override suspend fun subscribeAccountBalanceUpdatePoint(
        chain: Chain,
        chainAsset: Chain.Asset,
        accountId: AccountId,
    ): Flow<TransferableBalanceUpdatePoint> {
        // Only ever invoked from RealCrossChainTransactor (XCM arrival detection), which is Substrate-only -
        // Tron can never be an XCM cross-chain destination, so this is intentionally never reachable.
        throw UnsupportedOperationException("Tron does not support XCM-style balance update points")
    }

    override suspend fun startSyncingBalance(
        chain: Chain,
        chainAsset: Chain.Asset,
        metaAccount: MetaAccount,
        accountId: AccountId,
        subscriptionBuilder: SharedRequestsBuilder
    ): Flow<BalanceSyncUpdate> {
        val baseUrl = chain.requireTronGridBaseUrl()
        val address = chain.addressOf(accountId)

        return pollingBalanceFlow { tronGridApi.fetchNativeBalance(baseUrl, address) }
            .map { balance ->
                assetCache.updateNonLockableAsset(metaAccount.id, chainAsset, balance)

                BalanceSyncUpdate.NoCause
            }
    }
}
