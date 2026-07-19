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
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.BitcoinApi
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.RealBitcoinApi
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.RetrofitBitcoinApi
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.transaction.BitcoinTransactionService
import io.novafoundation.nova.feature_wallet_impl.data.network.bitcoin.transaction.RealBitcoinTransactionService
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.StaticAssetSource
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.bitcoinNative.BitcoinNativeAssetBalance
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.UnsupportedAssetHistory
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.bitcoinNative.BitcoinNativeAssetTransfers
import javax.inject.Qualifier

@Qualifier
annotation class BitcoinNativeAssets

/**
 * Bitcoin support: `balance` (REST polling against mempool.space) and `transfers` (client-side UTXO selection,
 * BIP143 signing, raw broadcast - see `RealBitcoinTransactionService` for the full construction/signing notes).
 *
 * `history` remains unsupported (out of scope for this phase, same as the rest of the app's `Unsupported*`
 * stubs used for asset types without history support - see `TronAssetsModule` for the identical precedent).
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
    fun provideBitcoinTransactionService(
        accountRepository: AccountRepository,
        signerProvider: SignerProvider,
        bitcoinApi: BitcoinApi,
    ): BitcoinTransactionService = RealBitcoinTransactionService(
        accountRepository = accountRepository,
        signerProvider = signerProvider,
        bitcoinApi = bitcoinApi
    )

    @Provides
    @FeatureScope
    fun provideBitcoinNativeBalance(assetCache: AssetCache, bitcoinApi: BitcoinApi) = BitcoinNativeAssetBalance(assetCache, bitcoinApi)

    @Provides
    @FeatureScope
    fun provideBitcoinNativeAssetTransfers(
        bitcoinTransactionService: BitcoinTransactionService,
        assetSourceRegistry: AssetSourceRegistry,
    ) = BitcoinNativeAssetTransfers(bitcoinTransactionService, assetSourceRegistry)

    @Provides
    @BitcoinNativeAssets
    @FeatureScope
    fun provideBitcoinNativeAssetSource(
        bitcoinNativeAssetBalance: BitcoinNativeAssetBalance,
        bitcoinNativeAssetTransfers: BitcoinNativeAssetTransfers,
        unsupportedAssetHistory: UnsupportedAssetHistory,
    ): AssetSource = StaticAssetSource(
        transfers = bitcoinNativeAssetTransfers,
        balance = bitcoinNativeAssetBalance,
        history = unsupportedAssetHistory
    )
}
