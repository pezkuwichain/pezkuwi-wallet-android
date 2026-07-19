package io.novafoundation.nova.feature_wallet_impl.data.network.solana.transaction

import io.novafoundation.nova.common.utils.Base58
import io.novafoundation.nova.common.utils.Precision
import io.novafoundation.nova.common.utils.SolanaTransaction
import io.novafoundation.nova.common.utils.TokenSymbol
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.signer.NovaSigner
import io.novafoundation.nova.feature_account_api.data.signer.SignerProvider
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_wallet_impl.data.network.solana.SolanaApi
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.encrypt.SignatureWrapper
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignedRaw
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SignerPayloadRaw
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner

// Same guaranteed-non-null-return wrappers as RealTronTransactionServiceTest, and for the same reason - see
// that test's class doc for the full writeup.
private fun <T> eq(value: T): T = Mockito.eq(value) ?: value

@Suppress("UNCHECKED_CAST")
private fun <T> any(): T {
    Mockito.any<T>()
    return null as T
}

@Suppress("UNCHECKED_CAST")
private fun <T> argThat(matcher: (T) -> Boolean): T {
    Mockito.argThat(ArgumentMatcher<T> { matcher(it) })
    return null as T
}

private fun <T> whenever(methodCall: T?) = Mockito.`when`(methodCall)

/**
 * Covers [RealSolanaTransactionService.transact]/[RealSolanaTransactionService.calculateFee] - the
 * message-build/sign/broadcast pipeline. Reuses the exact sender/recipient/blockhash/amount fixture that
 * [io.novafoundation.nova.common.utils.SolanaTransactionTest] cross-validated against the `solders` Python
 * library, so a regression in what THIS service hands to the signer/broadcaster is caught independently of
 * that lower-level wire-format test.
 */
@RunWith(MockitoJUnitRunner::class)
class RealSolanaTransactionServiceTest {

    @Mock
    lateinit var accountRepository: AccountRepository

    @Mock
    lateinit var signerProvider: SignerProvider

    @Mock
    lateinit var solanaApi: SolanaApi

    @Mock
    lateinit var metaAccount: MetaAccount

    @Mock
    lateinit var signer: NovaSigner

    private lateinit var subject: RealSolanaTransactionService

    private val senderPublicKey = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c".fromHex()
    private val recipientPublicKey = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394".fromHex()
    private val recipientAddress = Base58.encode(recipientPublicKey)
    private val recentBlockhash = ByteArray(32)
    private val lamports = 123456789L

    private val baseUrl = "https://api.mainnet-beta.solana.com"
    private val chain = solanaChain(baseUrl)

    private val expectedTransferMessage = SolanaTransaction.buildTransferMessage(senderPublicKey, recipientPublicKey, lamports, recentBlockhash)

    @Before
    fun setup() {
        subject = RealSolanaTransactionService(accountRepository, signerProvider, solanaApi)

        whenever(metaAccount.accountIdIn(eq(chain))).thenReturn(senderPublicKey)
        whenever(signerProvider.rootSignerFor(eq(metaAccount))).thenReturn(signer)
    }

    @Test
    fun `transact should build the transfer message, sign it raw with skipMessageHashing, and broadcast the serialized signed transaction`(): Unit = runBlocking {
        val signature = ByteArray(64) { it.toByte() }
        val fakeSignedRaw = SignedRaw(
            SignerPayloadRaw(message = expectedTransferMessage, accountId = senderPublicKey, skipMessageHashing = true),
            SignatureWrapper.Ed25519(signature = signature)
        )

        whenever(solanaApi.fetchLatestBlockhash(eq(baseUrl))).thenReturn(recentBlockhash)
        whenever(signer.signRaw(any())).thenReturn(fakeSignedRaw)
        whenever(solanaApi.broadcastTransaction(eq(baseUrl), any())).thenReturn("some-broadcast-signature")

        val result = subject.transact(
            chain = chain,
            origin = TransactionOrigin.Wallet(metaAccount),
            recipientAddress = recipientAddress,
            presetFee = null,
            amountLamports = lamports.toBigInteger()
        )

        assertTrue(result.isSuccess)
        assertEquals("some-broadcast-signature", result.getOrThrow().hash)

        // The message actually handed to the signer must be the exact compiled transfer message, not some other
        // byte sequence - a regression here would silently produce a signature over the wrong bytes.
        verify(signer).signRaw(
            argThat<SignerPayloadRaw> { payload ->
                payload.message.contentEquals(expectedTransferMessage) &&
                    payload.accountId.contentEquals(senderPublicKey) &&
                    payload.skipMessageHashing
            }
        )

        // The broadcast transaction must be compact-u16(1) + the raw 64-byte Ed25519 signature + the message,
        // exactly as SolanaTransaction.serializeSigned defines it - no DER/other re-encoding, unlike Bitcoin.
        val expectedSignedTransaction = SolanaTransaction.serializeSigned(expectedTransferMessage, signature)
        verify(solanaApi).broadcastTransaction(baseUrl, expectedSignedTransaction)
    }

    @Test
    fun `calculateFee should price a real message built against the latest blockhash and return it as SolanaFee`(): Unit = runBlocking {
        whenever(solanaApi.fetchLatestBlockhash(eq(baseUrl))).thenReturn(recentBlockhash)

        val expectedFeeMessage = SolanaTransaction.buildTransferMessage(senderPublicKey, recipientPublicKey, lamports = 0L, recentBlockhash)
        whenever(solanaApi.calculateFeeForMessage(eq(baseUrl), eq(expectedFeeMessage))).thenReturn(5000.toBigInteger())

        val fee = subject.calculateFee(
            chain = chain,
            origin = TransactionOrigin.Wallet(metaAccount),
            recipientAddress = recipientAddress,
            amountLamports = lamports.toBigInteger()
        )

        assertEquals(5000.toBigInteger(), fee.amount)
    }

    @Test
    fun `calculateFee should fall back to a placeholder recipient when the real one can't be parsed yet`(): Unit = runBlocking {
        whenever(solanaApi.fetchLatestBlockhash(eq(baseUrl))).thenReturn(recentBlockhash)
        whenever(solanaApi.calculateFeeForMessage(eq(baseUrl), any())).thenReturn(5000.toBigInteger())

        // A blank recipient (e.g. mid-typing in the send UI) must not throw - Solana's fee only depends on the
        // signature count, not on which recipient is used, so this should still price successfully.
        val fee = subject.calculateFee(
            chain = chain,
            origin = TransactionOrigin.Wallet(metaAccount),
            recipientAddress = "",
            amountLamports = lamports.toBigInteger()
        )

        assertEquals(5000.toBigInteger(), fee.amount)
    }

    private fun solanaChain(baseUrl: String): Chain {
        val solAsset = Chain.Asset(
            icon = null,
            id = 0,
            priceId = "solana",
            chainId = "solana:mainnet",
            symbol = TokenSymbol("SOL"),
            precision = Precision(9),
            buyProviders = emptyMap(),
            sellProviders = emptyMap(),
            staking = emptyList(),
            type = Chain.Asset.Type.SolanaNative,
            source = Chain.Asset.Source.DEFAULT,
            name = "Solana",
            enabled = true
        )

        return Chain(
            id = "solana:mainnet",
            name = "Solana",
            assets = listOf(solAsset),
            nodes = Chain.Nodes(
                autoBalanceStrategy = Chain.Nodes.AutoBalanceStrategy.ROUND_ROBIN,
                wssNodeSelectionStrategy = Chain.Nodes.NodeSelectionStrategy.AutoBalance,
                nodes = listOf(Chain.Node(chainId = "solana:mainnet", unformattedUrl = baseUrl, name = "Solana", orderId = 0, isCustom = false))
            ),
            explorers = emptyList(),
            externalApis = emptyList(),
            icon = null,
            addressPrefix = 0,
            legacyAddressPrefix = null,
            types = null,
            isEthereumBased = false,
            isTronBased = false,
            isBitcoinBased = false,
            isSolanaBased = true,
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
