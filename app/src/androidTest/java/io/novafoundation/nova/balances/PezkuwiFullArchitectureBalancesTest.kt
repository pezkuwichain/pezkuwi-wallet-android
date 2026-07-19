package io.novafoundation.nova.balances

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.fromJson
import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.core_db.dao.AssetDao
import io.novafoundation.nova.core_db.dao.ChainAssetDao
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.di.DbApi
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novafoundation.nova.feature_assets.di.AssetsFeatureApi
import io.novafoundation.nova.runtime.BuildConfig.TEST_ASSETS_URL
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import kotlin.time.Duration.Companion.seconds

private data class AssetFixture(val chainId: String, val chainName: String, val assetId: Int, val symbol: String)
private data class AssetsFixtureFile(val account: String, val assets: List<AssetFixture>)

/**
 * Exercises the ACTUAL production balance-sync pipeline (BalancesUpdateSystem -> AssetCache/AssetDao) end to
 * end, unlike [BalancesIntegrationTest] which bypasses it entirely via a direct low-level storage query. This
 * is meant to answer one question with hard evidence, not speculation: for a real, well-funded mainnet Founder
 * account, does the app's real, running background sync ever write an `assets` cache row for every asset in
 * wallet-utils' pezkuwi_assets_for_testBalance.json (HEZ/PEZ/USDT/DOT/ETH/BTC across Pezkuwi's chains) - not
 * just the native balance on one chain, which is all the older [BalancesIntegrationTest] fixture covers.
 *
 * A single watch-only account is created and selected once, so a single BalancesUpdateSystem run has to
 * successfully sync every asset in the fixture - this is what actually caught the 2026-07-09 HEZ-on-Asset-Hub
 * silent sync failure (5 of 6 assets on that chain synced fine; only HEZ silently never did).
 *
 * BalancesUpdateSystem.start() is a cold flow - in production it's only ever collected by RootInteractor,
 * which is wired to the root Activity/ViewModel lifecycle. A bare instrumented test never launches that
 * Activity, so we collect it ourselves here via the same AssetsFeatureApi.updateSystem instance the real app
 * uses, instead of relying on app UI lifecycle to start it.
 *
 * If this test fails, the standard per-updater error logs already wired into BalancesUpdateSystem/
 * FullSyncPaymentUpdater/NativeAssetBalance/StatemineAssetBalance show exactly which decision branch or
 * exception is responsible - not another layer of inference from silence.
 */
class PezkuwiFullArchitectureBalancesTest {

    // Standard BIP44 Ethereum derivation (m/44'/60'/0'/0/0) from the same already-verified Founder mnemonic
    // used for the substrate address below - this is exactly what Nova/Pezkuwi Wallet's own unified account
    // creation derives when a single seed produces both a substrate and an Ethereum account. No dedicated
    // "founder EVM wallet" record exists anywhere else, so this is the correct, non-fabricated way to get a
    // real EVM address to test USDT-on-Ethereum sync with - it doesn't need to hold any balance, since this
    // test only asserts a row gets written (see below), not that the balance is non-zero.
    private val founderEthereumAddress = "0x1aa2EA1292c62BdC6E49E0C12134263efc73713A"
    private val ethereumChainId = "eip155:1"

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val dbApi = FeatureUtils.getFeature<DbApi>(context, DbApi::class.java)
    private val metaAccountDao = dbApi.metaAccountDao()
    private val assetDao: AssetDao = dbApi.provideAssetDao()
    private val chainAssetDao: ChainAssetDao = dbApi.chainAssetDao()

    private val assetsFeatureApi = FeatureUtils.getFeature<AssetsFeatureApi>(context, AssetsFeatureApi::class.java)

    @Test
    fun testPezkuwiEcosystemAssetsActuallySync() = runBlocking {
        val fixture: AssetsFixtureFile = Gson().fromJson(URL(TEST_ASSETS_URL).readText())

        val updateSystemJob = launch { assetsFeatureApi.updateSystem.start().collect {} }

        try {
            val metaId = insertAndSelectWatchAccount(metaAccountDao, fixture.account, founderEthereumAddress)

            // Ethereum's USDT isn't in the JSON fixture because its assetId isn't a fixed, known integer like
            // Substrate assets - EvmAssetsSyncService computes it as a hash of the ERC20 contract address at
            // sync time (see chainAssetIdOfErc20Token()), so it has to be resolved dynamically here instead of
            // hardcoded. This closes out the third of the three originally-reported symptoms (Tron disabled,
            // Pezkuwi tokens missing, USDT on Polkadot AH/Ethereum missing) - the first two are already covered
            // by the fixture-driven assets above.
            val ethereumUsdtAssetId = withTimeoutOrNull(30.seconds) {
                var assetId: Int? = null
                while (assetId == null) {
                    assetId = chainAssetDao.getEnabledAssets()
                        .firstOrNull { it.chainId == ethereumChainId && it.symbol == "USDT" }
                        ?.id
                    if (assetId == null) delay(2.seconds)
                }
                assetId
            }
            assertTrue(
                "USDT was never registered as an enabled asset on Ethereum (chainId=$ethereumChainId) within 30s - " +
                    "EvmAssetsSyncService may have failed to sync from EVM_ASSETS_URL.",
                ethereumUsdtAssetId != null
            )

            val stillMissing = (
                fixture.assets +
                    AssetFixture(ethereumChainId, "Ethereum", ethereumUsdtAssetId!!, "USDT")
                ).toMutableList()

            withTimeoutOrNull(120.seconds) {
                while (stillMissing.isNotEmpty()) {
                    val found = stillMissing.filter { asset ->
                        assetDao.getAsset(metaId, asset.chainId, asset.assetId) != null
                    }
                    stillMissing.removeAll(found)
                    if (stillMissing.isNotEmpty()) delay(2.seconds)
                }
            }

            assertTrue(
                "No `assets` row was ever written for: ${stillMissing.joinToString { "${it.symbol} on ${it.chainName}" }} " +
                    "(metaId=$metaId) within 120s, out of ${fixture.assets.size + 1} total. The real BalancesUpdateSystem " +
                    "pipeline never completed a sync for these - check the standard FullSyncPaymentUpdater/" +
                    "NativeAssetBalance/StatemineAssetBalance/EvmErc20AssetBalance error logs in logcat.",
                stillMissing.isEmpty()
            )
        } finally {
            updateSystemJob.cancel()
        }
    }

    private suspend fun insertAndSelectWatchAccount(dao: MetaAccountDao, substrateAddress: String, ethereumAddress: String): Long {
        val accountId = substrateAddress.toAccountId()
        val evmAddress = ethereumAddress.removePrefix("0x").fromHex()

        val metaAccount = MetaAccountLocal(
            substratePublicKey = accountId,
            substrateCryptoType = CryptoType.SR25519,
            substrateAccountId = accountId,
            ethereumPublicKey = null,
            ethereumAddress = evmAddress,
            name = "PezkuwiFullArchitectureBalancesTest",
            parentMetaId = null,
            isSelected = false,
            position = 0,
            type = MetaAccountLocal.Type.WATCH_ONLY,
            status = MetaAccountLocal.Status.ACTIVE,
            globallyUniqueId = MetaAccountLocal.generateGloballyUniqueId(),
            typeExtras = null
        )

        val metaId = dao.insertMetaAccount(metaAccount)
        dao.selectMetaAccount(metaId)

        return metaId
    }
}
