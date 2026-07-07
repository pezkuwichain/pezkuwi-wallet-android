package io.novafoundation.nova.feature_account_impl.data

import io.novafoundation.nova.common.utils.DEFAULT_DERIVATION_PATH
import io.novafoundation.nova.common.utils.ethereumAddressToAccountId
import io.novafoundation.nova.common.utils.toTronAddress
import io.novafoundation.nova.common.utils.tronPublicKeyToAccountId
import io.novafoundation.nova.feature_account_impl.data.secrets.TRON_DEFAULT_DERIVATION_PATH
import io.novasama.substrate_sdk_android.encrypt.junction.BIP32JunctionDecoder
import io.novasama.substrate_sdk_android.encrypt.keypair.bip32.Bip32EcdsaKeypairFactory
import io.novasama.substrate_sdk_android.encrypt.keypair.bip32.generate
import io.novasama.substrate_sdk_android.encrypt.seed.bip39.Bip39SeedFactory
import io.novasama.substrate_sdk_android.extensions.asEthereumPublicKey
import io.novasama.substrate_sdk_android.extensions.toAccountId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-checks Tron address derivation (Phase 1, read-only support) against values independently computed
 * outside this codebase (Python: hashlib/ecdsa/pycryptodome, none of which are used by the app itself) for the
 * standard BIP39 test mnemonic "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon
 * abandon about".
 *
 * The Ethereum test case is included first as a control: it reproduces the exact same well-known reference
 * address for this mnemonic that ethers.js/MetaMask documentation uses
 * (0x9858EfFD232B4033E47d90003D41EC34EcaEda94 at m/44'/60'/0'/0/0), which validates that this project's BIP32 +
 * secp256k1 + keccak pipeline (`Bip32EcdsaKeypairFactory` + `asEthereumPublicKey().toAccountId()`, from
 * `substrate_sdk_android`) behaves as expected. The Tron test case then reuses the exact same pipeline under
 * Tron's own SLIP-44 coin-type-195 derivation path, which necessarily yields a different keypair (and therefore
 * a different address) even though the math is identical - this is expected, not a bug.
 */
class TronDerivationTest {

    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `should derive well-known reference Ethereum address for the standard test mnemonic`() {
        val expectedAccountId = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94".ethereumAddressToAccountId()

        val seed = Bip39SeedFactory.deriveSeed(testMnemonic, password = null)
        val keypair = Bip32EcdsaKeypairFactory.generate(seed.seed, BIP32JunctionDecoder.DEFAULT_DERIVATION_PATH)

        val actualAccountId = keypair.publicKey.asEthereumPublicKey().toAccountId().value

        assertArrayEquals(expectedAccountId, actualAccountId)
    }

    @Test
    fun `should derive Tron address for the standard test mnemonic at the coin-195 path`() {
        // Independently verified via a from-scratch Python implementation (hashlib SHA-256 for Base58Check,
        // pycryptodome Keccak-256, `ecdsa` for secp256k1/BIP32) - see task verification notes.
        val expectedTronAddress = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"

        val seed = Bip39SeedFactory.deriveSeed(testMnemonic, password = null)
        val keypair = Bip32EcdsaKeypairFactory.generate(seed.seed, TRON_DEFAULT_DERIVATION_PATH)

        val accountId = keypair.publicKey.tronPublicKeyToAccountId()
        val actualTronAddress = accountId.toTronAddress()

        assertEquals(expectedTronAddress, actualTronAddress)
    }
}
