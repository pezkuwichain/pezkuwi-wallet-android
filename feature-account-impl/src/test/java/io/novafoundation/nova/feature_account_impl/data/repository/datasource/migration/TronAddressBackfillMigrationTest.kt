package io.novafoundation.nova.feature_account_impl.data.repository.datasource.migration

import io.novafoundation.nova.common.data.secrets.v2.KeyPairSchema
import io.novafoundation.nova.common.data.secrets.v2.MetaAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.SecretStoreV2
import io.novafoundation.nova.common.data.secrets.v2.mapKeypairStructToKeypair
import io.novafoundation.nova.common.data.secrets.v2.tronKeypair
import io.novafoundation.nova.common.data.storage.Preferences
import io.novafoundation.nova.common.utils.invoke
import io.novafoundation.nova.common.utils.tronAddressToAccountId
import io.novafoundation.nova.common.utils.tronPublicKeyToAccountId
import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.core_db.dao.MetaAccountDao
import io.novafoundation.nova.core_db.model.chain.account.MetaAccountLocal
import io.novafoundation.nova.feature_account_impl.data.secrets.AccountSecretsFactory
import io.novasama.substrate_sdk_android.encrypt.json.JsonDecoder
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import io.novasama.substrate_sdk_android.scale.EncodableStruct
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner

// Same guaranteed-non-null-return wrappers as RealTronTransactionServiceTest, and for the same reason: the
// shared test_shared eq()/any() helpers crash with "eq(...) must not be null" once Mockito's genuinely-null
// runtime return flows into a Kotlin non-null-typed parameter - see that test's class doc for the full writeup.
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
 * This is the money-safety-critical half of the Tron backfill fix (see TronAddressBackfillMigration's class
 * doc for the full context): a wrong derivation here would silently give a pre-existing wallet a Tron address
 * it does NOT actually control, or fail to skip an account it shouldn't touch. Uses a real (non-mocked)
 * AccountSecretsFactory so the actual BIP32/secp256k1 derivation math runs for real, and asserts against the
 * same live-TronGrid-cross-validated reference address TronDerivationTest already established for the standard
 * BIP39 test mnemonic, so this test and that one are pinned to the same known-good vector.
 */
@RunWith(MockitoJUnitRunner::class)
class TronAddressBackfillMigrationTest {

    @Mock
    lateinit var preferences: Preferences

    @Mock
    lateinit var secretStoreV2: SecretStoreV2

    @Mock
    lateinit var metaAccountDao: MetaAccountDao

    @Mock
    lateinit var jsonDecoder: JsonDecoder

    private lateinit var subject: TronAddressBackfillMigration

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val expectedTronAccountId = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH".tronAddressToAccountId()

    @Before
    fun setup() {
        // Real factory, not a mock - the whole point of this test is to exercise the actual derivation.
        val accountSecretsFactory = AccountSecretsFactory(jsonDecoder)
        subject = TronAddressBackfillMigration(preferences, secretStoreV2, metaAccountDao, accountSecretsFactory)
    }

    @Test
    fun `migrationNeeded should reflect the persisted flag`(): Unit = runBlocking {
        // The flag's preference key is a private implementation detail of the production class - matched via
        // any() here rather than duplicating the literal key string, which would let this test pass even if
        // that string silently drifted out of sync with the production code.
        whenever(preferences.getBoolean(any(), Mockito.anyBoolean())).thenReturn(false)
        assertTrue(subject.migrationNeeded())

        whenever(preferences.getBoolean(any(), Mockito.anyBoolean())).thenReturn(true)
        assertTrue(!subject.migrationNeeded())
    }

    @Test
    fun `migrate should derive and persist the well-known reference Tron address for a pre-existing mnemonic account`(): Unit = runBlocking {
        val account = secretsAccount(id = 42, substrateCryptoType = CryptoType.SR25519)
        val secrets = accountSecrets(entropy = MnemonicCreator.fromWords(testMnemonic).entropy, tronKeypair = null)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(metaAccountDao.getMetaAccount(eq(42L))).thenReturn(account)
        whenever(secretStoreV2.getMetaAccountSecrets(eq(42L))).thenReturn(secrets)

        subject.migrate()

        verify(metaAccountDao).updateMetaAccount(
            argThat<MetaAccountLocal> { updated ->
                updated.id == 42L && updated.tronAddress.contentEquals(expectedTronAccountId)
            }
        )

        verify(secretStoreV2).putMetaAccountSecrets(
            eq(42L),
            argThat<EncodableStruct<MetaAccountSecrets>> { updatedSecrets ->
                val tronKeypairStruct = updatedSecrets.tronKeypair
                tronKeypairStruct != null &&
                    mapKeypairStructToKeypair(tronKeypairStruct).publicKey.tronPublicKeyToAccountId().contentEquals(expectedTronAccountId)
            }
        )
    }

    @Test
    fun `migrate should skip an account that already has a Tron keypair`(): Unit = runBlocking {
        val account = secretsAccount(id = 7, substrateCryptoType = CryptoType.SR25519)
        val existingTronKeypair = KeyPairSchema { keypair ->
            keypair[KeyPairSchema.PublicKey] = ByteArray(33) { 9 }
            keypair[KeyPairSchema.PrivateKey] = ByteArray(32) { 8 }
            keypair[KeyPairSchema.Nonce] = null
        }
        val secrets = accountSecrets(entropy = MnemonicCreator.fromWords(testMnemonic).entropy, tronKeypair = existingTronKeypair)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(secretStoreV2.getMetaAccountSecrets(eq(7L))).thenReturn(secrets)

        subject.migrate()

        verify(metaAccountDao, never()).updateMetaAccount(any())
        // metaId is a primitive Long parameter - same anyBoolean() reasoning applies, use anyLong().
        verify(secretStoreV2, never()).putMetaAccountSecrets(Mockito.anyLong(), any())
    }

    @Test
    fun `migrate should skip an account with no entropy (raw-seed import, not a mnemonic)`(): Unit = runBlocking {
        val account = secretsAccount(id = 11, substrateCryptoType = CryptoType.SR25519)
        val secrets = accountSecrets(entropy = null, tronKeypair = null)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(secretStoreV2.getMetaAccountSecrets(eq(11L))).thenReturn(secrets)

        subject.migrate()

        verify(metaAccountDao, never()).updateMetaAccount(any())
        // metaId is a primitive Long parameter - same anyBoolean() reasoning applies, use anyLong().
        verify(secretStoreV2, never()).putMetaAccountSecrets(Mockito.anyLong(), any())
    }

    @Test
    fun `migrate should skip an account with no stored secrets at all (watch-only, Ledger, etc)`(): Unit = runBlocking {
        val account = secretsAccount(id = 13, substrateCryptoType = CryptoType.SR25519)

        whenever(metaAccountDao.getMetaAccounts()).thenReturn(listOf(account))
        whenever(secretStoreV2.getMetaAccountSecrets(eq(13L))).thenReturn(null)

        subject.migrate()

        verify(metaAccountDao, never()).updateMetaAccount(any())
        // metaId is a primitive Long parameter - same anyBoolean() reasoning applies, use anyLong().
        verify(secretStoreV2, never()).putMetaAccountSecrets(Mockito.anyLong(), any())
    }

    private fun secretsAccount(id: Long, substrateCryptoType: CryptoType): MetaAccountLocal {
        return MetaAccountLocal(
            substratePublicKey = ByteArray(32) { 1 },
            substrateCryptoType = substrateCryptoType,
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

    private fun accountSecrets(entropy: ByteArray?, tronKeypair: EncodableStruct<KeyPairSchema>?): EncodableStruct<MetaAccountSecrets> {
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
            tronKeypair = tronKeypair?.let(::mapKeypairStructToKeypair),
            tronDerivationPath = null
        )
    }
}
