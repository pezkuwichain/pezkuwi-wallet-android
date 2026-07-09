package io.novafoundation.nova.balances

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.core_db.dao.AssetDao
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.di.DbApi
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novafoundation.nova.feature_assets.di.AssetsFeatureApi
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the ACTUAL production balance-sync pipeline (BalancesUpdateSystem -> AssetCache/AssetDao) end to
 * end, unlike [BalancesIntegrationTest] which bypasses it entirely via a direct low-level storage query. This
 * is meant to answer one question with hard evidence, not speculation: does the app's real, running background
 * sync ever write an `assets` cache row for HEZ on the Pezkuwi Asset Hub chain, for a real, well-funded account?
 *
 * BalancesUpdateSystem.start() is a cold flow - in production it's only ever collected by RootInteractor,
 * which is wired to the root Activity/ViewModel lifecycle. A bare instrumented test never launches that
 * Activity, so we collect it ourselves here via the same AssetsFeatureApi.updateSystem instance the real app
 * uses, instead of relying on app UI lifecycle to start it.
 *
 * If this test fails, the standard per-updater error logs already wired into BalancesUpdateSystem/
 * FullSyncPaymentUpdater/NativeAssetBalance show exactly which decision branch or exception is responsible -
 * not another layer of inference from silence.
 */
class PezkuwiFullArchitectureBalancesTest {

    // Mainnet Founder account (SS58, generic substrate prefix) - verified live via @pezkuwi/api on 2026-07-09
    // to hold a substantial non-zero, non-frozen free HEZ balance on Pezkuwi Asset Hub (180,297.80 HEZ).
    private val founderSubstrateAddress = "5CyuFfbF95rzBxru7c9yEsX4XmQXUxpLUcbj9RLg9K1cGiiF"
    private val pezkuwiAssetHubChainId = "e7c15092dcbe3f320260ddbbc685bfceed9125a3b3d8436db2766201dec3b949"
    private val hezAssetId = 0

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val dbApi = FeatureUtils.getFeature<DbApi>(context, DbApi::class.java)
    private val metaAccountDao = dbApi.metaAccountDao()
    private val assetDao: AssetDao = dbApi.provideAssetDao()

    private val assetsFeatureApi = FeatureUtils.getFeature<AssetsFeatureApi>(context, AssetsFeatureApi::class.java)

    @Test
    fun testPezkuwiAssetHubHezBalanceActuallySyncs() = runBlocking {
        val updateSystemJob = launch { assetsFeatureApi.updateSystem.start().collect {} }

        try {
            val metaId = insertAndSelectFounderWatchAccount(metaAccountDao)

            val assetRow = withTimeoutOrNull(90.seconds) {
                while (true) {
                    val asset = assetDao.getAsset(metaId, pezkuwiAssetHubChainId, hezAssetId)
                    if (asset != null) return@withTimeoutOrNull asset

                    delay(2.seconds)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

            assertNotNull(
                "No `assets` row was ever written for HEZ on Pezkuwi Asset Hub (metaId=$metaId) within 90s. " +
                    "The real BalancesUpdateSystem pipeline never completed a sync for this asset - check the " +
                    "standard FullSyncPaymentUpdater/NativeAssetBalance error logs in logcat.",
                assetRow
            )
        } finally {
            updateSystemJob.cancel()
        }
    }

    private suspend fun insertAndSelectFounderWatchAccount(dao: MetaAccountDao): Long {
        val accountId = founderSubstrateAddress.toAccountId()

        val metaAccount = MetaAccountLocal(
            substratePublicKey = accountId,
            substrateCryptoType = CryptoType.SR25519,
            substrateAccountId = accountId,
            ethereumPublicKey = null,
            ethereumAddress = null,
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
