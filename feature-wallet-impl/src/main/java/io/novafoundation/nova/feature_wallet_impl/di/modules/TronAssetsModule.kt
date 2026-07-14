package io.novafoundation.nova.feature_wallet_impl.di.modules

import dagger.Module
import dagger.Provides
import io.novafoundation.nova.common.data.network.NetworkApiCreator
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.feature_account_api.data.signer.SignerProvider
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_wallet_api.data.cache.AssetCache
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.AssetSource
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.AssetSourceRegistry
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.StaticAssetSource
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.trc20.Trc20AssetBalance
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.tronNative.TronNativeAssetBalance
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.UnsupportedAssetHistory
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.trc20.Trc20AssetTransfers
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.tronNative.TronNativeAssetTransfers
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.RealTronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.RetrofitTronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.RealTronTransactionService
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction.TronTransactionService
import javax.inject.Qualifier

@Qualifier
annotation class TronNativeAssets

@Qualifier
annotation class Trc20Assets

/**
 * Tron/TRC-20 support.
 *
 * Phase 1 (read-only): `balance`.
 * Phase 2 (send/transfer, this module): `transfers`, via [TronTransactionService] - construction/broadcast
 * through TronGrid's own REST endpoints, signing through the app's existing Ethereum-style ECDSA signer (see
 * `RealTronTransactionService` for the full verification notes). No Energy/Bandwidth staking or rental: only
 * Tron's default protocol behavior (auto-burning TRX when free resources are insufficient) is estimated and
 * enforced.
 *
 * `history` remains unsupported (out of scope for this phase, same as the rest of the app's `Unsupported*`
 * stubs used for asset types without history support).
 */
@Module
class TronAssetsModule {

    @Provides
    @FeatureScope
    fun provideRetrofitTronGridApi(
        networkApiCreator: NetworkApiCreator
    ): RetrofitTronGridApi = networkApiCreator.create(RetrofitTronGridApi::class.java)

    @Provides
    @FeatureScope
    fun provideTronGridApi(retrofitTronGridApi: RetrofitTronGridApi): TronGridApi = RealTronGridApi(retrofitTronGridApi)

    @Provides
    @FeatureScope
    fun provideTronTransactionService(
        accountRepository: AccountRepository,
        signerProvider: SignerProvider,
        tronGridApi: TronGridApi,
    ): TronTransactionService = RealTronTransactionService(
        accountRepository = accountRepository,
        signerProvider = signerProvider,
        tronGridApi = tronGridApi
    )

    @Provides
    @FeatureScope
    fun provideTronNativeBalance(assetCache: AssetCache, tronGridApi: TronGridApi) = TronNativeAssetBalance(assetCache, tronGridApi)

    @Provides
    @FeatureScope
    fun provideTrc20Balance(assetCache: AssetCache, tronGridApi: TronGridApi) = Trc20AssetBalance(assetCache, tronGridApi)

    @Provides
    @FeatureScope
    fun provideTronNativeAssetTransfers(
        tronTransactionService: TronTransactionService,
        assetSourceRegistry: AssetSourceRegistry,
    ) = TronNativeAssetTransfers(tronTransactionService, assetSourceRegistry)

    @Provides
    @FeatureScope
    fun provideTrc20AssetTransfers(
        tronTransactionService: TronTransactionService,
        assetSourceRegistry: AssetSourceRegistry,
    ) = Trc20AssetTransfers(tronTransactionService, assetSourceRegistry)

    @Provides
    @TronNativeAssets
    @FeatureScope
    fun provideTronNativeAssetSource(
        tronNativeAssetBalance: TronNativeAssetBalance,
        tronNativeAssetTransfers: TronNativeAssetTransfers,
        unsupportedAssetHistory: UnsupportedAssetHistory,
    ): AssetSource = StaticAssetSource(
        transfers = tronNativeAssetTransfers,
        balance = tronNativeAssetBalance,
        history = unsupportedAssetHistory
    )

    @Provides
    @Trc20Assets
    @FeatureScope
    fun provideTrc20AssetSource(
        trc20AssetBalance: Trc20AssetBalance,
        trc20AssetTransfers: Trc20AssetTransfers,
        unsupportedAssetHistory: UnsupportedAssetHistory,
    ): AssetSource = StaticAssetSource(
        transfers = trc20AssetTransfers,
        balance = trc20AssetBalance,
        history = unsupportedAssetHistory
    )
}
