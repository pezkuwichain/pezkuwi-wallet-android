package io.novafoundation.nova.feature_wallet_impl.di.modules

import dagger.Module
import dagger.Provides
import io.novafoundation.nova.common.data.network.NetworkApiCreator
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.feature_wallet_api.data.cache.AssetCache
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.AssetSource
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.StaticAssetSource
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.trc20.Trc20AssetBalance
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.tronNative.TronNativeAssetBalance
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.UnsupportedAssetHistory
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.UnsupportedAssetTransfers
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.RealTronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.RetrofitTronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi
import javax.inject.Qualifier

@Qualifier
annotation class TronNativeAssets

@Qualifier
annotation class Trc20Assets

/**
 * Tron/TRC-20 support - Phase 1, read-only.
 *
 * Only `balance` is implemented for real; `transfers`/`history` reuse the same `Unsupported*` stubs the rest of
 * the app uses for asset types with no send/history support yet (see `UnsupportedAssetsModule`). This is
 * intentional: no transaction-building/signing code exists for Tron yet - that is future, separate work.
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
    fun provideTronNativeBalance(assetCache: AssetCache, tronGridApi: TronGridApi) = TronNativeAssetBalance(assetCache, tronGridApi)

    @Provides
    @FeatureScope
    fun provideTrc20Balance(assetCache: AssetCache, tronGridApi: TronGridApi) = Trc20AssetBalance(assetCache, tronGridApi)

    @Provides
    @TronNativeAssets
    @FeatureScope
    fun provideTronNativeAssetSource(
        tronNativeAssetBalance: TronNativeAssetBalance,
        unsupportedAssetTransfers: UnsupportedAssetTransfers,
        unsupportedAssetHistory: UnsupportedAssetHistory,
    ): AssetSource = StaticAssetSource(
        transfers = unsupportedAssetTransfers,
        balance = tronNativeAssetBalance,
        history = unsupportedAssetHistory
    )

    @Provides
    @Trc20Assets
    @FeatureScope
    fun provideTrc20AssetSource(
        trc20AssetBalance: Trc20AssetBalance,
        unsupportedAssetTransfers: UnsupportedAssetTransfers,
        unsupportedAssetHistory: UnsupportedAssetHistory,
    ): AssetSource = StaticAssetSource(
        transfers = unsupportedAssetTransfers,
        balance = trc20AssetBalance,
        history = unsupportedAssetHistory
    )
}
