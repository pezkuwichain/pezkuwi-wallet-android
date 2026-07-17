package io.novafoundation.nova.feature_account_impl.data.repository.datasource.migration

import io.novafoundation.nova.common.data.secrets.v2.KeyPairSchema
import io.novafoundation.nova.common.data.secrets.v2.MetaAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.SecretStoreV2
import io.novafoundation.nova.common.data.secrets.v2.mapKeypairStructToKeypair
import io.novafoundation.nova.common.data.secrets.v2.solanaKeypair
import io.novafoundation.nova.common.utils.invoke
import io.novafoundation.nova.common.utils.solanaAddressToAccountId
import io.novafoundation.nova.common.utils.toSolanaAddress
import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import io.novasama.substrate_sdk_android.scale.EncodableStruct
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner

// Same guaranteed-non-null-return wrappers as TronAddressBackfillMigrationTest, for the same reason - see
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
 * Mirrors [TronAddressBackfillMigrationTest]'s design (see that class's doc for the full context on why this
 * matters). Unlike Tron/Bitcoin's migration test, [SolanaAddressBackfillMigration] doesn't go through
 * [io.novafoundation.nova.feature_account_impl.data.secrets.AccountSecretsFactory] (Solana's Ed25519
 * derivation is a different code path entirely - see that class's constructor), so there's no factory to mock
 * or use for real - the derivation math (SLIP-0010 via [io.novafoundation.nova.common.utils.Bip32Ed25519KeypairFactory])
 * runs directly. Asserts against the same live-cross-validated reference address
 * [io.novafoundation.nova.common.utils.SolanaAddressTest] already established for the standard BIP39 test
 * mnemonic, so this test and that one are pinned to the same known-good vector.
 */
@RunWith(MockitoJUnitRunner::class)
class SolanaAddressBackfillMigrationTest {

    @Mock
    lateinit var secretStoreV2: SecretStoreV2

    @Mock
    lateinit var metaAccountDao: MetaAccountDao

    private lateinit var subject: SolanaAddressBackfillMigration

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val expectedSolanaAccountId = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk".solanaAddressToAccountId()

    @Before
    fun setup() {
        subject = SolanaAddressBackfillMigration(secretStoreV2, metaAccountDao)
    }

    @Test
    fun `migrate should derive and persist the well-known reference Solana address for a pre-existing mnemonic account`(): Unit = runBlocking {
        val account = secretsAccount(id = 42)
        val secrets = accountSecrets(entropy = MnemonicCreator.fromWords(testMnemonic).entropy, solanaKeypair = null)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(metaAccountDao.getMetaAccount(eq(42L))).thenReturn(account)
        whenever(secretStoreV2.getMetaAccountSecrets(eq(42L))).thenReturn(secrets)

        subject.migrate()

        verify(metaAccountDao).updateMetaAccount(
            argThat<MetaAccountLocal> { updated ->
                updated.id == 42L && updated.solanaAddress.contentEquals(expectedSolanaAccountId)
            }
        )

        verify(secretStoreV2).putMetaAccountSecrets(
            eq(42L),
            argThat<EncodableStruct<MetaAccountSecrets>> { updatedSecrets ->
                val solanaKeypairStruct = updatedSecrets.solanaKeypair
                solanaKeypairStruct != null &&
                    mapKeypairStructToKeypair(solanaKeypairStruct).publicKey.toSolanaAddress() == "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
            }
        )
    }

    @Test
    fun `migrate should skip an account that already has a Solana keypair`(): Unit = runBlocking {
        val account = secretsAccount(id = 7)
        val existingSolanaKeypair = KeyPairSchema { keypair ->
            keypair[KeyPairSchema.PublicKey] = ByteArray(32) { 9 }
            keypair[KeyPairSchema.PrivateKey] = ByteArray(32) { 8 }
            keypair[KeyPairSchema.Nonce] = null
        }
        val secrets = accountSecrets(entropy = MnemonicCreator.fromWords(testMnemonic).entropy, solanaKeypair = existingSolanaKeypair)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(secretStoreV2.getMetaAccountSecrets(eq(7L))).thenReturn(secrets)

        subject.migrate()

        verify(metaAccountDao, never()).updateMetaAccount(any())
        verify(secretStoreV2, never()).putMetaAccountSecrets(Mockito.anyLong(), any())
    }

    @Test
    fun `migrate should skip an account with no entropy (raw-seed import, not a mnemonic)`(): Unit = runBlocking {
        val account = secretsAccount(id = 11)
        val secrets = accountSecrets(entropy = null, solanaKeypair = null)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(secretStoreV2.getMetaAccountSecrets(eq(11L))).thenReturn(secrets)

        subject.migrate()

        verify(metaAccountDao, never()).updateMetaAccount(any())
        verify(secretStoreV2, never()).putMetaAccountSecrets(Mockito.anyLong(), any())
    }

    @Test
    fun `migrate should skip an account with no stored secrets at all (watch-only, Ledger, etc)`(): Unit = runBlocking {
        val account = secretsAccount(id = 13)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(secretStoreV2.getMetaAccountSecrets(eq(13L))).thenReturn(null)

        subject.migrate()

        verify(metaAccountDao, never()).updateMetaAccount(any())
        verify(secretStoreV2, never()).putMetaAccountSecrets(Mockito.anyLong(), any())
    }

    private fun secretsAccount(id: Long): MetaAccountLocal {
        return MetaAccountLocal(
            substratePublicKey = ByteArray(32) { 1 },
            substrateCryptoType = CryptoType.SR25519,
            substrateAccountId = ByteArray(32) { 2 },
            ethereumPublicKey = null,
            ethereumAddress = null,
            name = "Test account",
            parentMetaId = null,
            isSelected = true,
            position = 0,
            type = MetaAccountLocal.Type.SECRETS,
            status = MetaAccountLocal.Status.ACTIVE,
            globallyUniqueId = "test-guid-$id",
            typeExtras = null
        ).also { it.id = id }
    }

    private fun accountSecrets(entropy: ByteArray?, solanaKeypair: EncodableStruct<KeyPairSchema>?): EncodableStruct<MetaAccountSecrets> {
        val dummySubstrateKeypair = KeyPairSchema { keypair ->
            keypair[KeyPairSchema.PublicKey] = ByteArray(32) { 5 }
            keypair[KeyPairSchema.PrivateKey] = ByteArray(32) { 6 }
            keypair[KeyPairSchema.Nonce] = ByteArray(8) { 7 }
        }

        return MetaAccountSecrets(
            substrateKeyPair = mapKeypairStructToKeypair(dummySubstrateKeypair),
            entropy = entropy,
            substrateSeed = null,
            substrateDerivationPath = null,
            ethereumKeypair = null,
            ethereumDerivationPath = null,
            solanaKeypair = solanaKeypair?.let(::mapKeypairStructToKeypair),
            solanaDerivationPath = null
        )
    }
}
