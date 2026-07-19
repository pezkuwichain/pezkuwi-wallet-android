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
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.balances.solanaNative.SolanaNativeAssetBalance
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.history.UnsupportedAssetHistory
import io.novafoundation.nova.feature_wallet_impl.data.network.blockchain.assets.transfers.solanaNative.SolanaNativeAssetTransfers
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.RealSolanaApi
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.RetrofitSolanaApi
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.SolanaApi
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.transaction.RealSolanaTransactionService
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.transaction.SolanaTransactionService
import javax.inject.Qualifier

@Qualifier
annotation class SolanaNativeAssets

/**
 * Solana support: `balance` (JSON-RPC `getBalance` polling) and `transfers` (client-side message build,
 * Ed25519 signing, `sendTransaction` broadcast - see `RealSolanaTransactionService` for the full notes).
 *
 * `history` remains unsupported (out of scope for this phase, same as the rest of the app's `Unsupported*`
 * stubs used for asset types without history support - see `BitcoinAssetsModule`/`TronAssetsModule` for the
 * identical precedent).
 */
@Module
class SolanaAssetsModule {

    @Provides
    @FeatureScope
    fun provideRetrofitSolanaApi(
        networkApiCreator: NetworkApiCreator
    ): RetrofitSolanaApi = networkApiCreator.create(RetrofitSolanaApi::class.java)

    @Provides
    @FeatureScope
    fun provideSolanaApi(retrofitSolanaApi: RetrofitSolanaApi): SolanaApi = RealSolanaApi(retrofitSolanaApi)

    @Provides
    @FeatureScope
    fun provideSolanaTransactionService(
        accountRepository: AccountRepository,
        signerProvider: SignerProvider,
        solanaApi: SolanaApi,
    ): SolanaTransactionService = RealSolanaTransactionService(
        accountRepository = accountRepository,
        signerProvider = signerProvider,
        solanaApi = solanaApi
    )

    @Provides
    @FeatureScope
    fun provideSolanaNativeBalance(assetCache: AssetCache, solanaApi: SolanaApi) = SolanaNativeAssetBalance(assetCache, solanaApi)

    @Provides
    @FeatureScope
    fun provideSolanaNativeAssetTransfers(
        solanaTransactionService: SolanaTransactionService,
        assetSourceRegistry: AssetSourceRegistry,
    ) = SolanaNativeAssetTransfers(solanaTransactionService, assetSourceRegistry)

    @Provides
    @SolanaNativeAssets
    @FeatureScope
    fun provideSolanaNativeAssetSource(
        solanaNativeAssetBalance: SolanaNativeAssetBalance,
        solanaNativeAssetTransfers: SolanaNativeAssetTransfers,
        unsupportedAssetHistory: UnsupportedAssetHistory,
    ): AssetSource = StaticAssetSource(
        transfers = solanaNativeAssetTransfers,
        balance = solanaNativeAssetBalance,
        history = unsupportedAssetHistory
    )
}
