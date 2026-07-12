package io.novafoundation.nova.feature_wallet_impl.di.modules

import dagger.Module
import dagger.Provides
import io.novafoundation.nova.common.data.network.NetworkApiCreator
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.feature_wallet_api.data.cache.AssetCache
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.assets.AssetSource
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.BitcoinApi
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.RealBitcoinApi
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.RetrofitBitcoinApi
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.StaticAssetSource
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.bitcoinNative.BitcoinNativeAssetBalance
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.UnsupportedAssetHistory
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.UnsupportedAssetTransfers
import javax.inject.Qualifier

@Qualifier
annotation class BitcoinNativeAssets

/**
 * Bitcoin support - Phase 4, read-only.
 *
 * Only `balance` is implemented for real; `transfers`/`history` reuse the same `Unsupported*` stubs the rest of
 * the app uses for asset types with no send/history support yet (see `TronAssetsModule` for the identical
 * Phase-1 precedent this mirrors). Send support is separate, later work.
 */
@Module
class BitcoinAssetsModule {

    @Provides
    @FeatureScope
    fun provideRetrofitBitcoinApi(
        networkApiCreator: NetworkApiCreator
    ): RetrofitBitcoinApi = networkApiCreator.create(RetrofitBitcoinApi::class.java)

    @Provides
    @FeatureScope
    fun provideBitcoinApi(retrofitBitcoinApi: RetrofitBitcoinApi): BitcoinApi = RealBitcoinApi(retrofitBitcoinApi)

    @Provides
    @FeatureScope
    fun provideBitcoinNativeBalance(assetCache: AssetCache, bitcoinApi: BitcoinApi) = BitcoinNativeAssetBalance(assetCache, bitcoinApi)

    @Provides
    @BitcoinNativeAssets
    @FeatureScope
    fun provideBitcoinNativeAssetSource(
        bitcoinNativeAssetBalance: BitcoinNativeAssetBalance,
        unsupportedAssetTransfers: UnsupportedAssetTransfers,
        unsupportedAssetHistory: UnsupportedAssetHistory,
    ): AssetSource = StaticAssetSource(
        transfers = unsupportedAssetTransfers,
        balance = bitcoinNativeAssetBalance,
        history = unsupportedAssetHistory
    )
}
