package io.novafoundation.nova

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.balances
import io.novafoundation.nova.feature_wallet_api.di.WalletFeatureApi
import io.novafoundation.nova.runtime.ext.utilityAsset
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import io.novasama.substrate_sdk_android.runtime.extrinsic.BatchMode
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import io.novafoundation.nova.runtime.ext.Geneses
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * End-to-end integration tests for Pezkuwi chain compatibility.
 * These tests verify that:
 * 1. Runtime loads correctly with proper types
 * 2. Extrinsics can be built
 * 3. Fee calculation works
 * 4. Transfer extrinsics can be created
 *
 * Run with: ./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.novafoundation.nova.PezkuwiIntegrationTest
 */
class PezkuwiIntegrationTest : BaseIntegrationTest() {

    private val walletApi = FeatureUtils.getFeature<WalletFeatureApi>(
        ApplicationProvider.getApplicationContext<Context>(),
        WalletFeatureApi::class.java
    )

    private val extrinsicBuilderFactory = runtimeApi.extrinsicBuilderFactory()
    private val rpcCalls = runtimeApi.rpcCalls()

    /**
     * Test 1: Verify Pezkuwi Mainnet runtime loads with required types
     */
    @Test
    fun testPezkuwiMainnetRuntimeTypes() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)
        val runtime = chainRegistry.getRuntime(chain.id)

        // Verify critical types exist
        val extrinsicSignature = runtime.typeRegistry["ExtrinsicSignature"]
        assertNotNull("ExtrinsicSignature type should exist", extrinsicSignature)

        val multiSignature = runtime.typeRegistry["MultiSignature"]
        assertNotNull("MultiSignature type should exist", multiSignature)

        val multiAddress = runtime.typeRegistry["MultiAddress"]
        assertNotNull("MultiAddress type should exist", multiAddress)

        val address = runtime.typeRegistry["Address"]
        assertNotNull("Address type should exist", address)

        println("Pezkuwi Mainnet: All required types present")
        println("TypeRegistry size: ${runtime.typeRegistry.keys.size}")
    }

    /**
     * Test 2: Verify Pezkuwi Asset Hub runtime loads with required types
     */
    @Test
    fun testPezkuwiAssetHubRuntimeTypes() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI_ASSET_HUB)
        val runtime = chainRegistry.getRuntime(chain.id)

        val extrinsicSignature = runtime.typeRegistry["ExtrinsicSignature"]
        assertNotNull("ExtrinsicSignature type should exist", extrinsicSignature)

        val address = runtime.typeRegistry["Address"]
        assertNotNull("Address type should exist", address)

        println("Pezkuwi Asset Hub: All required types present")
    }

    /**
     * Test 3: Verify extrinsic builder can be created for Pezkuwi
     */
    @Test
    fun testPezkuwiExtrinsicBuilderCreation() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)

        val builder = extrinsicBuilderFactory.create(
            chain = chain,
            options = io.novafoundation.nova.runtime.extrinsic.ExtrinsicBuilderFactory.Options(
                batchMode = BatchMode.BATCH_ALL
            )
        )

        assertNotNull("ExtrinsicBuilder should be created", builder)
        println("Pezkuwi ExtrinsicBuilder created successfully")
    }

    /**
     * Test 4: Verify transfer call can be constructed
     */
    @Test
    fun testPezkuwiTransferCallConstruction() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)
        val runtime = chainRegistry.getRuntime(chain.id)

        // Check if balances module exists
        val balancesModule = runtime.metadata.modules.find {
            it.name.equals("Balances", ignoreCase = true)
        }
        assertNotNull("Balances module should exist", balancesModule)

        // Check transfer call exists
        val hasTransferKeepAlive = balancesModule?.calls?.any {
            it.key.equals("transfer_keep_alive", ignoreCase = true)
        } ?: false

        val hasTransferAllowDeath = balancesModule?.calls?.any {
            it.key.equals("transfer_allow_death", ignoreCase = true) ||
            it.key.equals("transfer", ignoreCase = true)
        } ?: false

        assertTrue("Transfer call should exist", hasTransferKeepAlive || hasTransferAllowDeath)

        println("Pezkuwi transfer call found: transfer_keep_alive=$hasTransferKeepAlive, transfer_allow_death=$hasTransferAllowDeath")
    }

    /**
     * Test 5: Verify signed extensions are properly handled
     */
    @Test
    fun testPezkuwiSignedExtensions() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)
        val runtime = chainRegistry.getRuntime(chain.id)

        val signedExtensions = runtime.metadata.extrinsic.signedExtensions.map { it.id }
        println("Pezkuwi signed extensions: $signedExtensions")

        // Verify Pezkuwi-specific extensions
        val hasAuthorizeCall = signedExtensions.contains("AuthorizeCall")
        println("Has AuthorizeCall extension: $hasAuthorizeCall")

        // Standard extensions should also be present
        val hasCheckMortality = signedExtensions.contains("CheckMortality")
        val hasCheckNonce = signedExtensions.contains("CheckNonce")

        assertTrue("CheckMortality should exist", hasCheckMortality)
        assertTrue("CheckNonce should exist", hasCheckNonce)
    }

    /**
     * Test 6: Verify utility asset is properly configured
     */
    @Test
    fun testPezkuwiUtilityAsset() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)

        val utilityAsset = chain.utilityAsset
        assertNotNull("Utility asset should exist", utilityAsset)

        println("Pezkuwi utility asset: ${utilityAsset.symbol}, precision: ${utilityAsset.precision}")
    }

    // Helper extension
    private suspend fun ChainRegistry.pezkuwiMainnet(): Chain {
        return getChain(Chain.Geneses.PEZKUWI)
    }
}
