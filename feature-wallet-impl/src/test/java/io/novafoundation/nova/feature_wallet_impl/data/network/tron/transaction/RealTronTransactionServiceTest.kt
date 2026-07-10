package io.novafoundation.nova.feature_wallet_impl.data.network.tron.transaction

import com.google.gson.JsonObject
import io.novafoundation.nova.common.utils.Precision
import io.novafoundation.nova.common.utils.TokenSymbol
import io.novafoundation.nova.common.utils.sha256
import io.novafoundation.nova.common.utils.toTronHexAddress
import io.novafoundation.nova.common.utils.tronAddressToAccountId
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.signer.NovaSigner
import io.novafoundation.nova.feature_account_api.data.signer.SignerProvider
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.TronGridApi
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronAccountResourceResponse
import io.novafoundation.nova.feature_wallet_impl.data.network.tron.model.TronUnsignedTransactionResponse
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.test_shared.any
import io.novafoundation.nova.test_shared.argThat
import io.novafoundation.nova.test_shared.eq
import io.novafoundation.nova.test_shared.whenever
import io.novasama.substrate_sdk_android.encrypt.SignatureWrapper
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignedRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import java.math.BigInteger

/**
 * Covers the native-TRX branch of [RealTronTransactionService.transact] - the sign/broadcast pipeline this class'
 * own doc comment describes (sha256(raw_data) signed via the existing Ethereum-style ECDSA-raw-hash primitive,
 * assembled as a 65-byte r+s+v signature) had no test at all before this: [Trc20TransferAbiTest] only covers the
 * TRC-20 ABI-encoding side, and [TronDerivationTest]/[io.novafoundation.nova.common.utils.TronAddressTest] only
 * cover address derivation/formatting, not transaction construction or signing.
 *
 * The owner address used throughout is the same one [TronDerivationTest] cross-validated against the standard
 * BIP39 test mnemonic ("abandon x11 about" at the coin-195 path) and against live TronGrid data - reusing it here
 * keeps every Tron test in this codebase anchored to a single, independently-verified real-world address instead
 * of an arbitrary one. The recipient and raw_data_hex/txID pair are self-consistent fixtures created for this
 * test only (unlike [Trc20TransferAbiTest]'s ABI vector, this raw_data_hex was not captured live against
 * TronGrid - constructing a real `TransferContract` protobuf is out of scope here, since this class deliberately
 * never encodes one itself, see its class doc) - what matters for these tests is that this service correctly
 * hashes/signs/forwards whatever raw_data TronGrid returns, which a self-consistent fixture exercises just as
 * well as a live-captured one.
 */
@RunWith(MockitoJUnitRunner::class)
class RealTronTransactionServiceTest {

    @Mock
    lateinit var accountRepository: AccountRepository

    @Mock
    lateinit var signerProvider: SignerProvider

    @Mock
    lateinit var tronGridApi: TronGridApi

    @Mock
    lateinit var metaAccount: MetaAccount

    @Mock
    lateinit var signer: NovaSigner

    private lateinit var subject: RealTronTransactionService

    private val ownerAccountId = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH".tronAddressToAccountId()
    private val ownerHex = ownerAccountId.toTronHexAddress()

    private val recipientAccountId = "dfd8703a5c753e17ed52a96a29cea9d425538dfe".fromHex()
    private val recipientHex = recipientAccountId.toTronHexAddress()

    private val amountSun = BigInteger.valueOf(1_000_000)

    private val baseUrl = "https://api.trongrid.io"
    private val chain = tronChain(baseUrl)

    // Self-consistent fixture: rawDataHex ++ its own sha256 as txID - see class doc.
    private val rawDataHex = "0a027a1e2208d1e2b3f4a5b6c7d840e8c896e8b7325a67080112630a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412320a1541dfd8703a5c753e17ed52a96a29cea9d425538dfe1215415d10da10f5c60a8e2d5e3c0a70e1e7f3c1b2a3e41880ade20470a08fc9c9e8b732"
    private val expectedTxId = rawDataHex.fromHex().sha256().toHexString(withPrefix = false)

    @Before
    fun setup() {
        subject = RealTronTransactionService(accountRepository, signerProvider, tronGridApi)

        whenever(metaAccount.accountIdIn(eq(chain))).thenReturn(ownerAccountId)
        whenever(signerProvider.rootSignerFor(eq(metaAccount))).thenReturn(signer)
    }

    @Test
    fun `transact with Native intent should build, sign with sha256(raw_data), and broadcast a 65-byte r+s+v signature`(): Unit = runBlocking {
        val unsigned = TronUnsignedTransactionResponse(
            visible = false,
            txID = expectedTxId,
            rawData = JsonObject(),
            rawDataHex = rawDataHex
        )

        val r = ByteArray(32) { (it + 1).toByte() }
        val s = ByteArray(32) { (it + 33).toByte() }
        val v = byteArrayOf(27)
        val fakeSignedRaw = SignedRaw(
            SignerPayloadRaw(message = expectedTxId.fromHex(), accountId = ownerAccountId, skipMessageHashing = true),
            SignatureWrapper.Ecdsa(v = v, r = r, s = s)
        )

        whenever(tronGridApi.createNativeTransfer(eq(baseUrl), eq(ownerHex), eq(recipientHex), eq(amountSun))).thenReturn(unsigned)
        whenever(signer.signRaw(any())).thenReturn(fakeSignedRaw)
        whenever(tronGridApi.broadcastTransaction(eq(baseUrl), eq(unsigned), any())).thenReturn("some-broadcast-tx-hash")

        val result = subject.transact(
            chain = chain,
            origin = TransactionOrigin.Wallet(metaAccount),
            recipient = recipientAccountId,
            presetFee = null,
            intent = TronTransactionIntent.Native(amountSun)
        )

        assertTrue(result.isSuccess)
        assertEquals("some-broadcast-tx-hash", result.getOrThrow().hash)

        // The message actually handed to the signer must be sha256(raw_data), not raw_data or txID itself - a
        // regression here would silently produce a signature over the wrong bytes. ByteArray has reference
        // equality in Kotlin, so this must compare contents (contentEquals), not rely on SignerPayloadRaw.equals().
        val expectedMessage = rawDataHex.fromHex().sha256()
        verify(signer).signRaw(
            argThat<SignerPayloadRaw> { payload: SignerPayloadRaw ->
                payload.message.contentEquals(expectedMessage) &&
                    payload.accountId.contentEquals(ownerAccountId) &&
                    payload.skipMessageHashing
            }
        )

        // Tron expects a flat 65-byte r(32)+s(32)+v(1) hex signature - assembled by hand in production code, not
        // by any library, so this is the one place that byte order/length could silently regress.
        val expectedSignatureHex = (r + s + v).toHexString(withPrefix = false)
        verify(tronGridApi).broadcastTransaction(baseUrl, unsigned, expectedSignatureHex)
    }

    @Test
    fun `transact should refuse to sign when TronGrid's txID does not match sha256(raw_data)`(): Unit = runBlocking {
        val tamperedTxId = "0".repeat(64) // deliberately wrong - does not match sha256(rawDataHex)
        val unsigned = TronUnsignedTransactionResponse(
            visible = false,
            txID = tamperedTxId,
            rawData = JsonObject(),
            rawDataHex = rawDataHex
        )

        whenever(tronGridApi.createNativeTransfer(eq(baseUrl), eq(ownerHex), eq(recipientHex), eq(amountSun))).thenReturn(unsigned)

        val result = subject.transact(
            chain = chain,
            origin = TransactionOrigin.Wallet(metaAccount),
            recipient = recipientAccountId,
            presetFee = null,
            intent = TronTransactionIntent.Native(amountSun)
        )

        assertTrue("expected a failed Result when txID doesn't match sha256(raw_data)", result.isFailure)

        // Signing (and therefore broadcasting) must never be attempted once the txID/raw_data mismatch is
        // detected - this is the guard that stops a tampered/malicious response from getting silently signed.
        verify(signer, never()).signRaw(any())
        verify(tronGridApi, never()).broadcastTransaction(any(), any(), any())
    }

    @Test
    fun `calculateFee for Native intent should charge bandwidth shortfall at the fallback price when TronGrid's own resource_endpoints are unavailable`(): Unit = runBlocking {
        val unsigned = TronUnsignedTransactionResponse(
            visible = false,
            txID = expectedTxId,
            rawData = JsonObject(),
            rawDataHex = rawDataHex
        )
        val txSizeBytes = rawDataHex.length / 2

        whenever(tronGridApi.createNativeTransfer(eq(baseUrl), eq(ownerHex), eq(recipientHex), eq(amountSun))).thenReturn(unsigned)
        whenever(tronGridApi.getAccountResource(eq(baseUrl), eq(ownerHex))).thenReturn(TronAccountResourceResponse())
        whenever(tronGridApi.getChainParameters(eq(baseUrl))).thenReturn(emptyMap<String, Long>())

        val fee = subject.calculateFee(
            chain = chain,
            origin = TransactionOrigin.Wallet(metaAccount),
            recipient = recipientAccountId,
            intent = TronTransactionIntent.Native(amountSun)
        )

        // Zero available bandwidth (empty TronAccountResourceResponse) -> the whole tx size is billed, at the
        // fallback bandwidth price (1000 sun/byte) since getChainParameters returned no getTransactionFee entry.
        val expectedFeeSun = BigInteger.valueOf(txSizeBytes.toLong()) * BigInteger.valueOf(1000)
        assertEquals(expectedFeeSun, fee.amount)
    }

    private fun tronChain(baseUrl: String): Chain {
        val trxAsset = Chain.Asset(
            icon = null,
            id = 0,
            priceId = "tron",
            chainId = "tron:mainnet",
            symbol = TokenSymbol("TRX"),
            precision = Precision(6),
            buyProviders = emptyMap(),
            sellProviders = emptyMap(),
            staking = emptyList(),
            type = Chain.Asset.Type.TronNative,
            source = Chain.Asset.Source.DEFAULT,
            name = "Tron",
            enabled = true
        )

        return Chain(
            id = "tron:mainnet",
            name = "Tron",
            assets = listOf(trxAsset),
            nodes = Chain.Nodes(
                autoBalanceStrategy = Chain.Nodes.AutoBalanceStrategy.ROUND_ROBIN,
                wssNodeSelectionStrategy = Chain.Nodes.NodeSelectionStrategy.AutoBalance,
                nodes = listOf(Chain.Node(chainId = "tron:mainnet", unformattedUrl = baseUrl, name = "TronGrid", orderId = 0, isCustom = false))
            ),
            explorers = emptyList(),
            externalApis = emptyList(),
            icon = null,
            addressPrefix = 0,
            legacyAddressPrefix = null,
            types = null,
            isEthereumBased = false,
            isTronBased = true,
            isTestNet = false,
            source = Chain.Source.DEFAULT,
            hasSubstrateRuntime = false,
            pushSupport = false,
            hasCrowdloans = false,
            supportProxy = false,
            governance = emptyList(),
            swap = emptyList(),
            customFee = emptyList(),
            multisigSupport = false,
            connectionState = Chain.ConnectionState.FULL_SYNC,
            parentId = null,
            additional = null
        )
    }
}
