package io.novafoundation.nova

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.balances
import io.novafoundation.nova.feature_account_api.data.signer.CallExecutionType
import io.novafoundation.nova.feature_account_api.data.signer.NovaSigner
import io.novafoundation.nova.feature_account_api.data.signer.SigningContext
import io.novafoundation.nova.feature_account_api.data.signer.SigningMode
import io.novafoundation.nova.feature_account_api.data.signer.SubmissionHierarchy
import io.novafoundation.nova.feature_account_api.data.signer.setSignerData
import io.novafoundation.nova.feature_account_api.domain.model.LightMetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_impl.domain.account.model.DefaultMetaAccount
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.nativeTransfer
import io.novafoundation.nova.feature_wallet_api.di.WalletFeatureApi
import io.novafoundation.nova.runtime.ext.Geneses
import io.novafoundation.nova.runtime.ext.utilityAsset
import io.novafoundation.nova.runtime.extrinsic.ExtrinsicBuilderFactory
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import io.novasama.substrate_sdk_android.encrypt.SignatureWrapper
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.extrinsic.BatchMode
import io.novasama.substrate_sdk_android.runtime.extrinsic.Nonce
import io.novasama.substrate_sdk_android.runtime.extrinsic.builder.ExtrinsicBuilder
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignedRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.InheritedImplication
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckNonce.Companion.setNonce
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.verifySignature.GeneralTransactionSigner
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.verifySignature.VerifySignature.Companion.setVerifySignature
import io.novasama.substrate_sdk_android.runtime.metadata.callOrNull
import io.novasama.substrate_sdk_android.runtime.metadata.moduleOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private val extrinsicBuilderFactory = runtimeApi.provideExtrinsicBuilderFactory()
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
        val balancesModule = runtime.metadata.moduleOrNull("Balances")
        assertNotNull("Balances module should exist", balancesModule)

        // Check transfer call exists
        val hasTransferKeepAlive = balancesModule?.callOrNull("transfer_keep_alive") != null
        val hasTransferAllowDeath = balancesModule?.callOrNull("transfer_allow_death") != null ||
            balancesModule?.callOrNull("transfer") != null

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

    /**
     * Test 7: Build and sign a transfer extrinsic (THIS IS THE CRITICAL TEST)
     * This test will catch "TypeReference is null" errors during signing
     */
    @Test
    fun testPezkuwiBuildSignedTransferExtrinsic() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)
        val signer = TestSigner()

        val builder = extrinsicBuilderFactory.create(
            chain = chain,
            options = ExtrinsicBuilderFactory.Options(BatchMode.BATCH)
        )

        // Add transfer call
        val recipientAccountId = ByteArray(32) { 2 }
        builder.nativeTransfer(accountId = recipientAccountId, amount = BigInteger.ONE)

        // Set signer data (this is where TypeReference errors can occur)
        try {
            with(builder) {
                signer.setSignerData(TestSigningContext(chain), SigningMode.SUBMISSION)
            }
            Log.d("PezkuwiTest", "Signer data set successfully")
        } catch (e: Exception) {
            Log.e("PezkuwiTest", "Failed to set signer data", e)
            fail("Failed to set signer data: ${e.message}")
        }

        // Build the extrinsic (this is where TypeReference errors can also occur)
        try {
            val extrinsic = builder.buildExtrinsic()
            assertNotNull("Built extrinsic should not be null", extrinsic)
            Log.d("PezkuwiTest", "Extrinsic built successfully: ${extrinsic.extrinsicHex}")
            println("Pezkuwi: Transfer extrinsic built and signed successfully!")
        } catch (e: Exception) {
            Log.e("PezkuwiTest", "Failed to build extrinsic", e)
            fail("Failed to build extrinsic: ${e.message}\nCause: ${e.cause?.message}")
        }
    }

    /**
     * Test 8: Build extrinsic for fee calculation (uses fake signature)
     */
    @Test
    fun testPezkuwiBuildFeeExtrinsic() = runTest {
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)
        val signer = TestSigner()

        val builder = extrinsicBuilderFactory.create(
            chain = chain,
            options = ExtrinsicBuilderFactory.Options(BatchMode.BATCH)
        )

        val recipientAccountId = ByteArray(32) { 2 }
        builder.nativeTransfer(accountId = recipientAccountId, amount = BigInteger.ONE)

        // Set signer data for FEE mode (uses fake signature)
        try {
            with(builder) {
                signer.setSignerData(TestSigningContext(chain), SigningMode.FEE)
            }
            val extrinsic = builder.buildExtrinsic()
            assertNotNull("Fee extrinsic should not be null", extrinsic)
            println("Pezkuwi: Fee extrinsic built successfully!")
        } catch (e: Exception) {
            Log.e("PezkuwiTest", "Failed to build fee extrinsic", e)
            fail("Failed to build fee extrinsic: ${e.message}")
        }
    }

    // Helper extension
    private suspend fun ChainRegistry.pezkuwiMainnet(): Chain {
        return getChain(Chain.Geneses.PEZKUWI)
    }

    // Test signer for building extrinsics without real keys
    private inner class TestSigner : NovaSigner, GeneralTransactionSigner {

        val accountId = ByteArray(32) { 1 }

        override suspend fun callExecutionType(): CallExecutionType {
            return CallExecutionType.IMMEDIATE
        }

        override val metaAccount: MetaAccount = DefaultMetaAccount(
            id = 0,
            globallyUniqueId = "0",
            substrateAccountId = accountId,
            substrateCryptoType = null,
            substratePublicKey = null,
            ethereumAddress = null,
            ethereumPublicKey = null,
            isSelected = true,
            name = "test",
            type = LightMetaAccount.Type.SECRETS,
            chainAccounts = emptyMap(),
            status = LightMetaAccount.Status.ACTIVE,
            parentMetaId = null
        )

        override suspend fun getSigningHierarchy(): SubmissionHierarchy {
            return SubmissionHierarchy(metaAccount, CallExecutionType.IMMEDIATE)
        }

        override suspend fun signRaw(payload: SignerPayloadRaw): SignedRaw {
            error("Not implemented")
        }

        context(ExtrinsicBuilder)
        override suspend fun setSignerDataForSubmission(context: SigningContext) {
            setNonce(BigInteger.ZERO)
            setVerifySignature(this@TestSigner, accountId)
        }

        context(ExtrinsicBuilder)
        override suspend fun setSignerDataForFee(context: SigningContext) {
            setSignerDataForSubmission(context)
        }

        override suspend fun submissionSignerAccountId(chain: Chain): AccountId {
            return accountId
        }

        override suspend fun maxCallsPerTransaction(): Int? {
            return null
        }

        override suspend fun signInheritedImplication(
            inheritedImplication: InheritedImplication,
            accountId: AccountId
        ): SignatureWrapper {
            // Return a fake Sr25519 signature for testing
            return SignatureWrapper.Sr25519(ByteArray(64))
        }
    }

    private class TestSigningContext(override val chain: Chain) : SigningContext {
        override suspend fun getNonce(accountId: AccountIdKey): Nonce {
            return Nonce.ZERO
        }
    }
}
