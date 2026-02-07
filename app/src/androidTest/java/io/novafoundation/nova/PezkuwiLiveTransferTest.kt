package io.novafoundation.nova

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.novafoundation.nova.common.address.AccountIdKey
import io.novafoundation.nova.common.di.FeatureUtils
import io.novafoundation.nova.common.utils.deriveSeed32
import io.novafoundation.nova.feature_account_api.data.signer.CallExecutionType
import io.novafoundation.nova.feature_account_api.data.signer.NovaSigner
import io.novafoundation.nova.feature_account_api.data.signer.SigningContext
import io.novafoundation.nova.feature_account_api.data.signer.SigningMode
import io.novafoundation.nova.feature_account_api.data.signer.SubmissionHierarchy
import io.novafoundation.nova.feature_account_api.data.signer.setSignerData
import io.novafoundation.nova.feature_account_api.domain.model.LightMetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_impl.domain.account.model.DefaultMetaAccount
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.TransferMode
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.nativeTransfer
import io.novafoundation.nova.feature_wallet_api.di.WalletFeatureApi
import io.novafoundation.nova.runtime.ext.Geneses
import io.novafoundation.nova.runtime.ext.requireGenesisHash
import io.novafoundation.nova.runtime.extrinsic.ExtrinsicBuilderFactory
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import io.novafoundation.nova.runtime.network.rpc.RpcCalls
import io.novasama.substrate_sdk_android.encrypt.EncryptionType
import io.novasama.substrate_sdk_android.encrypt.MultiChainEncryption
import io.novasama.substrate_sdk_android.encrypt.SignatureWrapper
import io.novasama.substrate_sdk_android.encrypt.keypair.Keypair
import io.novasama.substrate_sdk_android.encrypt.keypair.substrate.SubstrateKeypairFactory
import io.novasama.substrate_sdk_android.encrypt.seed.substrate.SubstrateSeedFactory
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.extrinsic.BatchMode
import io.novasama.substrate_sdk_android.runtime.extrinsic.Nonce
import io.novasama.substrate_sdk_android.runtime.extrinsic.builder.ExtrinsicBuilder
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.KeyPairSigner
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignedRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.InheritedImplication
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckNonce.Companion.setNonce
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.verifySignature.GeneralTransactionSigner
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.verifySignature.VerifySignature.Companion.setVerifySignature
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.signingPayload
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import io.novafoundation.nova.sr25519.BizinikiwSr25519
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/**
 * LIVE TRANSFER TEST - Transfers real HEZ tokens on Pezkuwi mainnet!
 *
 * Sender: 5DXv3Dc5xELckTgcYa2dm1TSZPgqDPxVDW3Cid4ALWpVjY3w
 * Recipient: 5HdY6U2UQF8wPwczP3SoQz28kQu1WJSBqxKGePUKG4M5QYdV
 * Amount: 5 HEZ
 *
 * Run with: ./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.novafoundation.nova.PezkuwiLiveTransferTest
 */
class PezkuwiLiveTransferTest : BaseIntegrationTest() {

    companion object {
        // Test wallet mnemonic
        private const val TEST_MNEMONIC = "crucial surge north silly divert throw habit fury zebra fabric tank output"

        // Sender address (derived from mnemonic)
        private const val SENDER_ADDRESS = "5DXv3Dc5xELckTgcYa2dm1TSZPgqDPxVDW3Cid4ALWpVjY3w"

        // Recipient address
        private const val RECIPIENT_ADDRESS = "5HdY6U2UQF8wPwczP3SoQz28kQu1WJSBqxKGePUKG4M5QYdV"

        // Amount: 5 HEZ (with 12 decimals)
        private val TRANSFER_AMOUNT = BigInteger("5000000000000") // 5 * 10^12
    }

    private val walletApi = FeatureUtils.getFeature<WalletFeatureApi>(
        ApplicationProvider.getApplicationContext<Context>(),
        WalletFeatureApi::class.java
    )

    private val extrinsicBuilderFactory = runtimeApi.provideExtrinsicBuilderFactory()
    private val rpcCalls = runtimeApi.rpcCalls()

    /**
     * LIVE TEST: Build and submit a real transfer on Pezkuwi mainnet
     */
    @Test(timeout = 120000) // 2 minute timeout
    fun testLiveTransfer5HEZ() = runTest {
        Log.d("LiveTransferTest", "=== STARTING LIVE TRANSFER TEST ===")
        Log.d("LiveTransferTest", "Sender: $SENDER_ADDRESS")
        Log.d("LiveTransferTest", "Recipient: $RECIPIENT_ADDRESS")
        Log.d("LiveTransferTest", "Amount: 5 HEZ")

        // Request full sync for Pezkuwi chain specifically
        Log.d("LiveTransferTest", "Requesting full sync for Pezkuwi chain...")
        chainRegistry.enableFullSync(Chain.Geneses.PEZKUWI)

        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)
        Log.d("LiveTransferTest", "Chain: ${chain.name}")

        // Create keypair from mnemonic
        val keypair = createKeypairFromMnemonic(TEST_MNEMONIC)
        Log.d("LiveTransferTest", "Keypair created, public key: ${keypair.publicKey.toHexString()}")

        // Create signer
        val signer = RealSigner(keypair, chain)
        Log.d("LiveTransferTest", "Signer created")

        // Get recipient account ID
        val recipientAccountId = RECIPIENT_ADDRESS.toAccountId()
        Log.d("LiveTransferTest", "Recipient AccountId: ${recipientAccountId.toHexString()}")

        // Get current nonce using sender's SS58 address
        val nonce = try {
            rpcCalls.getNonce(chain.id, SENDER_ADDRESS)
        } catch (e: Exception) {
            Log.e("LiveTransferTest", "Failed to get nonce, using 0", e)
            BigInteger.ZERO
        }
        Log.d("LiveTransferTest", "Current nonce: $nonce")

        // Create extrinsic builder
        val builder = extrinsicBuilderFactory.create(
            chain = chain,
            options = ExtrinsicBuilderFactory.Options(BatchMode.BATCH)
        )
        Log.d("LiveTransferTest", "ExtrinsicBuilder created")

        // Use default MORTAL era (same as @pezkuwi/api)
        Log.d("LiveTransferTest", "Using MORTAL era (default, same as @pezkuwi/api)")

        // Add transfer call with KEEP_ALIVE mode (same as @pezkuwi/api uses)
        builder.nativeTransfer(accountId = recipientAccountId, amount = TRANSFER_AMOUNT, mode = TransferMode.KEEP_ALIVE)
        Log.d("LiveTransferTest", "Transfer call added")

        // Set signer data for SUBMISSION (this is where TypeReference errors occur!)
        try {
            with(builder) {
                signer.setSignerData(RealSigningContext(chain, nonce), SigningMode.SUBMISSION)
            }
            Log.d("LiveTransferTest", "Signer data set successfully")
        } catch (e: Exception) {
            Log.e("LiveTransferTest", "FAILED to set signer data!", e)
            fail("Failed to set signer data: ${e.message}\nCause: ${e.cause?.message}\nStack: ${e.stackTraceToString()}")
            return@runTest
        }

        // Build the extrinsic
        val extrinsic = try {
            builder.buildExtrinsic()
        } catch (e: Exception) {
            Log.e("LiveTransferTest", "FAILED to build extrinsic!", e)
            fail("Failed to build extrinsic: ${e.message}\nCause: ${e.cause?.message}\nStack: ${e.stackTraceToString()}")
            return@runTest
        }

        assertNotNull("Extrinsic should not be null", extrinsic)
        Log.d("LiveTransferTest", "Extrinsic built: ${extrinsic.extrinsicHex}")

        // Submit the extrinsic
        Log.d("LiveTransferTest", "Submitting extrinsic to network...")
        try {
            val hash = rpcCalls.submitExtrinsic(chain.id, extrinsic)
            Log.d("LiveTransferTest", "=== TRANSFER SUBMITTED SUCCESSFULLY ===")
            Log.d("LiveTransferTest", "Transaction hash: $hash")
            println("LIVE TRANSFER SUCCESS! TX Hash: $hash")
        } catch (e: Exception) {
            Log.e("LiveTransferTest", "FAILED to submit extrinsic!", e)
            fail("Failed to submit extrinsic: ${e.message}")
        }
    }

    /**
     * Test to check type resolution in the runtime
     */
    @Test(timeout = 120000)
    fun testTypeResolution() = runTest {
        Log.d("LiveTransferTest", "=== TESTING TYPE RESOLUTION ===")

        // Request full sync for Pezkuwi chain
        chainRegistry.enableFullSync(Chain.Geneses.PEZKUWI)
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)
        val runtime = chainRegistry.getRuntime(chain.id)

        // Check critical types for extrinsic encoding
        val typesToCheck = listOf(
            "Address",
            "MultiAddress",
            "GenericMultiAddress",
            "ExtrinsicSignature",
            "MultiSignature",
            "pezsp_runtime::multiaddress::MultiAddress",
            "pezsp_runtime::MultiSignature",
            "pezsp_runtime.multiaddress.MultiAddress",
            "pezsp_runtime.MultiSignature",
            "GenericExtrinsic",
            "Extrinsic"
        )

        val results = mutableListOf<String>()
        for (typeName in typesToCheck) {
            val type = runtime.typeRegistry[typeName]
            val resolved = type?.let {
                try {
                    // Try to get the actual type, not just alias
                    it.toString()
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }
            }
            val status = if (type != null) "FOUND: $resolved" else "MISSING"
            results.add("  $typeName: $status")
            Log.d("LiveTransferTest", "$typeName: $status")
        }

        // Check if extrinsic signature type is defined in metadata
        val extrinsicMeta = runtime.metadata.extrinsic
        Log.d("LiveTransferTest", "Extrinsic version: ${extrinsicMeta.version}")
        Log.d("LiveTransferTest", "Signed extensions: ${extrinsicMeta.signedExtensions.map { it.id }}")

        // Log signed extension IDs
        for (ext in extrinsicMeta.signedExtensions) {
            Log.d("LiveTransferTest", "Extension: ${ext.id}")
        }

        // Just log the extension names - type access might be restricted
        Log.d("LiveTransferTest", "Signed extensions count: ${extrinsicMeta.signedExtensions.size}")

        // Log the extrinsic address type if available
        Log.d("LiveTransferTest", "RuntimeFactory diagnostics: ${io.novafoundation.nova.runtime.multiNetwork.runtime.RuntimeFactory.lastDiagnostics}")

        println("Type resolution results:\n${results.joinToString("\n")}")
    }

    /**
     * Test fee calculation (doesn't submit, just builds for fee estimation)
     */
    @Test(timeout = 120000)
    fun testFeeCalculation() = runTest {
        Log.d("LiveTransferTest", "=== TESTING FEE CALCULATION ===")

        // Request full sync for Pezkuwi chain
        chainRegistry.enableFullSync(Chain.Geneses.PEZKUWI)
        val chain = chainRegistry.getChain(Chain.Geneses.PEZKUWI)

        // First, log type registry state
        val runtime = chainRegistry.getRuntime(chain.id)
        Log.d("LiveTransferTest", "TypeRegistry has ExtrinsicSignature: ${runtime.typeRegistry["ExtrinsicSignature"] != null}")
        Log.d("LiveTransferTest", "TypeRegistry has MultiSignature: ${runtime.typeRegistry["MultiSignature"] != null}")
        Log.d("LiveTransferTest", "TypeRegistry has Address: ${runtime.typeRegistry["Address"] != null}")
        Log.d("LiveTransferTest", "TypeRegistry has MultiAddress: ${runtime.typeRegistry["MultiAddress"] != null}")

        val keypair = createKeypairFromMnemonic(TEST_MNEMONIC)
        val signer = RealSigner(keypair, chain)
        val recipientAccountId = RECIPIENT_ADDRESS.toAccountId()

        val builder = extrinsicBuilderFactory.create(
            chain = chain,
            options = ExtrinsicBuilderFactory.Options(BatchMode.BATCH)
        )

        builder.nativeTransfer(accountId = recipientAccountId, amount = TRANSFER_AMOUNT)

        // Set signer data for FEE mode
        try {
            with(builder) {
                signer.setSignerData(RealSigningContext(chain, BigInteger.ZERO), SigningMode.FEE)
            }
            Log.d("LiveTransferTest", "Signer data set, building extrinsic...")
            val extrinsic = builder.buildExtrinsic()
            assertNotNull("Fee extrinsic should not be null", extrinsic)
            Log.d("LiveTransferTest", "Extrinsic built, getting hex...")

            // The error happens when accessing extrinsicHex
            try {
                val hex = extrinsic.extrinsicHex
                Log.d("LiveTransferTest", "Fee extrinsic built: $hex")
                println("Fee calculation test PASSED!")
            } catch (e: Exception) {
                Log.e("LiveTransferTest", "FAILED accessing extrinsicHex!", e)
                fail("Failed to get extrinsic hex: ${e.message}\nCause: ${e.cause?.message}\nStack: ${e.stackTraceToString()}")
            }
        } catch (e: Exception) {
            Log.e("LiveTransferTest", "Fee calculation FAILED!", e)
            fail("Fee calculation failed: ${e.message}\nCause: ${e.cause?.message}")
        }
    }

    // Helper to create keypair from mnemonic
    private fun createKeypairFromMnemonic(mnemonic: String): Keypair {
        val seedResult = SubstrateSeedFactory.deriveSeed32(mnemonic, password = null)
        return SubstrateKeypairFactory.generate(EncryptionType.SR25519, seedResult.seed)
    }

    // Real signer using actual keypair with bizinikiwi context
    private inner class RealSigner(
        private val keypair: Keypair,
        private val chain: Chain
    ) : NovaSigner, GeneralTransactionSigner {

        val accountId: ByteArray = keypair.publicKey

        // Generate proper 96-byte keypair using BizinikiwSr25519 native library
        // This gives us the correct 64-byte secret key format for signing
        private val bizinikiwKeypair: ByteArray by lazy {
            val seedResult = SubstrateSeedFactory.deriveSeed32(TEST_MNEMONIC, password = null)
            BizinikiwSr25519.keypairFromSeed(seedResult.seed)
        }

        // Extract 64-byte secret key (32-byte scalar + 32-byte nonce)
        private val bizinikiwSecretKey: ByteArray by lazy {
            BizinikiwSr25519.secretKeyFromKeypair(bizinikiwKeypair)
        }

        // Extract 32-byte public key
        private val bizinikiwPublicKey: ByteArray by lazy {
            BizinikiwSr25519.publicKeyFromKeypair(bizinikiwKeypair)
        }

        private val keyPairSigner = KeyPairSigner(
            keypair,
            MultiChainEncryption.Substrate(EncryptionType.SR25519)
        )

        override suspend fun callExecutionType(): CallExecutionType {
            return CallExecutionType.IMMEDIATE
        }

        override val metaAccount: MetaAccount = DefaultMetaAccount(
            id = 0,
            globallyUniqueId = "test-wallet",
            substrateAccountId = accountId,
            substrateCryptoType = null,
            substratePublicKey = keypair.publicKey,
            ethereumAddress = null,
            ethereumPublicKey = null,
            isSelected = true,
            name = "Test Wallet",
            type = LightMetaAccount.Type.SECRETS,
            chainAccounts = emptyMap(),
            status = LightMetaAccount.Status.ACTIVE,
            parentMetaId = null
        )

        override suspend fun getSigningHierarchy(): SubmissionHierarchy {
            return SubmissionHierarchy(metaAccount, CallExecutionType.IMMEDIATE)
        }

        override suspend fun signRaw(payload: SignerPayloadRaw): SignedRaw {
            return keyPairSigner.signRaw(payload)
        }

        context(ExtrinsicBuilder)
        override suspend fun setSignerDataForSubmission(context: SigningContext) {
            val nonce = context.getNonce(AccountIdKey(accountId))
            setNonce(nonce)
            setVerifySignature(this@RealSigner, accountId)
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
            // Get the SDK's signing payload (SCALE format - same as @pezkuwi/api)
            val sdkPayloadBytes = inheritedImplication.signingPayload()

            Log.d("LiveTransferTest", "=== SIGNING PAYLOAD (SDK - SCALE) ===")
            Log.d("LiveTransferTest", "SDK Payload hex: ${sdkPayloadBytes.toHexString()}")
            Log.d("LiveTransferTest", "SDK Payload length: ${sdkPayloadBytes.size} bytes")

            // Debug: show first bytes to verify format
            if (sdkPayloadBytes.size >= 42) {
                val callData = sdkPayloadBytes.copyOfRange(0, 42)
                val extensions = sdkPayloadBytes.copyOfRange(42, sdkPayloadBytes.size)
                Log.d("LiveTransferTest", "Call data (42 bytes): ${callData.toHexString()}")
                Log.d("LiveTransferTest", "Extensions (${extensions.size} bytes): ${extensions.toHexString()}")
            }

            // Use BizinikiwSr25519 native library with "bizinikiwi" signing context
            Log.d("LiveTransferTest", "=== USING BIZINIKIWI CONTEXT ===")
            Log.d("LiveTransferTest", "Bizinikiwi public key: ${bizinikiwPublicKey.toHexString()}")
            Log.d("LiveTransferTest", "Bizinikiwi secret key size: ${bizinikiwSecretKey.size} bytes")

            val signatureBytes = BizinikiwSr25519.sign(
                publicKey = bizinikiwPublicKey,
                secretKey = bizinikiwSecretKey,
                message = sdkPayloadBytes
            )

            Log.d("LiveTransferTest", "=== SIGNATURE PRODUCED ===")
            Log.d("LiveTransferTest", "Signature bytes: ${signatureBytes.toHexString()}")
            Log.d("LiveTransferTest", "Signature length: ${signatureBytes.size} bytes")

            // Verify the signature locally before sending
            val verifyResult = BizinikiwSr25519.verify(signatureBytes, sdkPayloadBytes, bizinikiwPublicKey)
            Log.d("LiveTransferTest", "Local verification: $verifyResult")

            return SignatureWrapper.Sr25519(signatureBytes)
        }
    }

    private class RealSigningContext(
        override val chain: Chain,
        private val nonceValue: BigInteger
    ) : SigningContext {
        override suspend fun getNonce(accountId: AccountIdKey): Nonce {
            return Nonce.ZERO + nonceValue
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
