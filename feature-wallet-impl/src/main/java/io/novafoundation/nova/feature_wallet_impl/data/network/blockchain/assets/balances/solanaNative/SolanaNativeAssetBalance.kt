package io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.solanaNative

import io.novafoundation.nova.core.updater.SharedRequestsBuilder
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_wallet_api.data.cache.AssetCache
import io.novafoundation.nova.feature_wallet_api.data.cache.updateNonLockableAsset
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.AssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.BalanceSyncUpdate
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.ChainAssetBalance
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.balances.model.TransferableBalanceUpdatePoint
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.SolanaApi
import io.novafoundation.nova.runtime.ext.addressOf
import io.novafoundation.nova.runtime.ext.requireSolanaRpcBaseUrl
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.AccountId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.math.BigInteger

/**
 * Native SOL balance on a Solana-based chain. Read-only balance (Phase 1, mirrors
 * [io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.bitcoinNative.BitcoinNativeAssetBalance]'s
 * exact design): fetches via Solana's `getBalance` JSON-RPC method and polls for updates, since that method has
 * no push/subscription counterpart usable here.
 */
class SolanaNativeAssetBalance(
    private val assetCache: AssetCache,
    private val solanaApi: SolanaApi,
) : AssetBalance {

    override suspend fun startSyncingBalanceLocks(
        metaAccount: MetaAccount,
        chain: Chain,
        chainAsset: Chain.Asset,
        accountId: AccountId,
        subscriptionBuilder: SharedRequestsBuilder
    ): Flow<*> {
        // Solana native balance does not support locks
        return emptyFlow<Nothing>()
    }

    override fun isSelfSufficient(chainAsset: Chain.Asset): Boolean {
        return true
    }

    override suspend fun existentialDeposit(chainAsset: Chain.Asset): BigInteger {
        // Solana's closest analogue is per-account rent-exemption, which is a function of account *size*, not
        // a fixed protocol constant the way Substrate's ED is - not modeled yet (Phase 1, same open item as
        // Bitcoin's dust limit not being enforced at this layer).
        return BigInteger.ZERO
    }

    override suspend fun queryAccountBalance(chain: Chain, chainAsset: Chain.Asset, accountId: AccountId): ChainAssetBalance {
        val balance = solanaApi.fetchNativeBalance(chain.requireSolanaRpcBaseUrl(), chain.addressOf(accountId))

        return ChainAssetBalance.fromFree(chainAsset, balance)
    }

    override suspend fun subscribeAccountBalanceUpdatePoint(
        chain: Chain,
        chainAsset: Chain.Asset,
        accountId: AccountId,
    ): Flow<TransferableBalanceUpdatePoint> {
        // Only ever invoked from RealCrossChainTransactor (XCM arrival detection), which is Substrate-only -
        // Solana can never be an XCM cross-chain destination, so this is intentionally never reachable.
        throw UnsupportedOperationException("Solana does not support XCM-style balance update points")
    }

    override suspend fun startSyncingBalance(
        chain: Chain,
        chainAsset: Chain.Asset,
        metaAccount: MetaAccount,
        accountId: AccountId,
        subscriptionBuilder: SharedRequestsBuilder
    ): Flow<BalanceSyncUpdate> {
        val baseUrl = chain.requireSolanaRpcBaseUrl()
        val address = chain.addressOf(accountId)

        return pollingBalanceFlow { solanaApi.fetchNativeBalance(baseUrl, address) }
            .map { balance ->
                assetCache.updateNonLockableAsset(metaAccount.id, chainAsset, balance)

                BalanceSyncUpdate.NoCause
            }
    }
}
