package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.bitcoinNative

import io.novafoundation.nova.core.updater.SharedRequestsBuilder
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_wallet_api.data.cache.AssetCache
import io.novafoundation.nova.feature_wallet_api.data.cache.updateNonLockableAsset
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.AssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.BalanceSyncUpdate
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.ChainAssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.TransferableBalanceUpdatePoint
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.BitcoinApi
import io.novafoundation.nova.runtime.ext.addressOf
import io.novafoundation.nova.runtime.ext.requireMempoolSpaceBaseUrl
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.AccountId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.math.BigInteger

/**
 * Native BTC balance on a Bitcoin-based chain. Read-only (Phase 4): fetches via a mempool.space-style REST API
 * and polls for updates, since that API has no push/subscription mechanism. No transfer/history support here -
 * see `BitcoinAssetsModule` for how this is paired with `UnsupportedAssetTransfers`/`UnsupportedAssetHistory`.
 */
class BitcoinNativeAssetBalance(
    private val assetCache: AssetCache,
    private val bitcoinApi: BitcoinApi,
) : AssetBalance {

    override suspend fun startSyncingBalanceLocks(
        metaAccount: MetaAccount,
        chain: Chain,
        chainAsset: Chain.Asset,
        accountId: AccountId,
        subscriptionBuilder: SharedRequestsBuilder
    ): Flow<*> {
        // Bitcoin native balance does not support locks
        return emptyFlow<Nothing>()
    }

    override fun isSelfSufficient(chainAsset: Chain.Asset): Boolean {
        return true
    }

    override suspend fun existentialDeposit(chainAsset: Chain.Asset): BigInteger {
        // Bitcoin does not have an existential deposit concept (UTXO dust limit is enforced at the transfer level, not here)
        return BigInteger.ZERO
    }

    override suspend fun queryAccountBalance(chain: Chain, chainAsset: Chain.Asset, accountId: AccountId): ChainAssetBalance {
        val balance = bitcoinApi.fetchNativeBalance(chain.requireMempoolSpaceBaseUrl(), chain.addressOf(accountId))

        return ChainAssetBalance.fromFree(chainAsset, balance)
    }

    override suspend fun subscribeAccountBalanceUpdatePoint(
        chain: Chain,
        chainAsset: Chain.Asset,
        accountId: AccountId,
    ): Flow<TransferableBalanceUpdatePoint> {
        // Not on the critical sync path (mirrors TronNativeAssetBalance/EvmNativeAssetBalance) - out of scope for Phase 4.
        TODO("Not yet implemented")
    }

    override suspend fun startSyncingBalance(
        chain: Chain,
        chainAsset: Chain.Asset,
        metaAccount: MetaAccount,
        accountId: AccountId,
        subscriptionBuilder: SharedRequestsBuilder
    ): Flow<BalanceSyncUpdate> {
        val baseUrl = chain.requireMempoolSpaceBaseUrl()
        val address = chain.addressOf(accountId)

        return pollingBalanceFlow { bitcoinApi.fetchNativeBalance(baseUrl, address) }
            .map { balance ->
                assetCache.updateNonLockableAsset(metaAccount.id, chainAsset, balance)

                BalanceSyncUpdate.NoCause
            }
    }
}
