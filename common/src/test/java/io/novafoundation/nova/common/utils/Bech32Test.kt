package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All test vectors below are quoted verbatim from the official BIPs (fetched directly from
 * https://github.com/bitcoin/bips at implementation time), not invented for this test:
 * - BIP173 (https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki) for Bech32 checksum vectors.
 * - BIP350 (https://github.com/bitcoin/bips/blob/master/bip-0350.mediawiki) for Bech32m checksum vectors
 *   and the current (BIP350-superseding-BIP173) segwit address <-> scriptPubKey vectors - BIP173's own
 *   witness-v1+ vectors used plain Bech32 (since Bech32m didn't exist yet) and are now considered INVALID;
 *   only BIP173's witness-v0 vectors still apply unchanged under BIP350.
 */
class Bech32Test {

    @Test
    fun `valid Bech32 checksums should decode without throwing`() {
        val validBech32 = listOf(
            "A12UEL5L",
            "a12uel5l",
            "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
            "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
            "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
            "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
            "?1ezyfcl"
        )

        for (address in validBech32) {
            val decoded = Bech32.decode(address)
            assertEquals("$address should decode as Bech32 (not Bech32m)", Bech32.Encoding.BECH32, decoded.encoding)
        }
    }

    @Test
    fun `valid Bech32m checksums should decode without throwing`() {
        val validBech32m = listOf(
            "A1LQFN3A",
            "a1lqfn3a",
            "an83characterlonghumanreadablepartthatcontainsthetheexcludedcharactersbioandnumber11sg7hg6",
            "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx",
            "11llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllludsr8",
            "split1checkupstagehandshakeupstreamerranterredcaperredlc445v",
            "?1v759aa"
        )

        for (address in validBech32m) {
            val decoded = Bech32.decode(address)
            assertEquals("$address should decode as Bech32m (not Bech32)", Bech32.Encoding.BECH32M, decoded.encoding)
        }
    }

    @Test
    fun `mixed case Bech32 string should be rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Bech32.decode("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7")
        }
    }

    // --- Segwit address <-> scriptPubKey (BIP350's updated table) ---

    private fun expectedWitnessVersionAndProgram(scriptPubKeyHex: String): Pair<Int, ByteArray> {
        val script = scriptPubKeyHex.fromHex()
        val versionByte = script[0].toInt() and 0xff
        val witnessVersion = if (versionByte == 0) 0 else versionByte - 0x50
        val programLength = script[1].toInt() and 0xff
        val program = script.copyOfRange(2, 2 + programLength)
        return witnessVersion to program
    }

    @Test
    fun `known mainnet P2WPKH address should decode to the documented scriptPubKey`() {
        val address = "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"
        val (expectedVersion, expectedProgram) = expectedWitnessVersionAndProgram("0014751e76e8199196d454941c45d1b3a323f1433bd6")

        val decoded = SegwitAddress.decode("bc", address)

        assertEquals(expectedVersion, decoded.witnessVersion)
        assertTrue(decoded.witnessProgram.contentEquals(expectedProgram))
    }

    @Test
    fun `known testnet P2WSH address should decode to the documented scriptPubKey`() {
        val address = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"
        val (expectedVersion, expectedProgram) =
            expectedWitnessVersionAndProgram("00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262")

        val decoded = SegwitAddress.decode("tb", address)

        assertEquals(expectedVersion, decoded.witnessVersion)
        assertTrue(decoded.witnessProgram.contentEquals(expectedProgram))
    }

    @Test
    fun `known testnet P2WPKH address (all-zero-ish program) should decode correctly`() {
        val address = "tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy"
        val (expectedVersion, expectedProgram) =
            expectedWitnessVersionAndProgram("0020000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433")

        val decoded = SegwitAddress.decode("tb", address)

        assertEquals(expectedVersion, decoded.witnessVersion)
        assertTrue(decoded.witnessProgram.contentEquals(expectedProgram))
    }

    @Test
    fun `known witness v1 taproot-style address (Bech32m) should decode correctly`() {
        val address = "bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kt5nd6y"
        val (expectedVersion, expectedProgram) = expectedWitnessVersionAndProgram(
            "5128751e76e8199196d454941c45d1b3a323f1433bd6751e76e8199196d454941c45d1b3a323f1433bd6"
        )

        val decoded = SegwitAddress.decode("bc", address)

        assertEquals(1, expectedVersion)
        assertEquals(expectedVersion, decoded.witnessVersion)
        assertTrue(decoded.witnessProgram.contentEquals(expectedProgram))
    }

    @Test
    fun `P2WPKH encode should reproduce the exact known mainnet address (lowercase)`() {
        val (_, program) = expectedWitnessVersionAndProgram("0014751e76e8199196d454941c45d1b3a323f1433bd6")

        val encoded = SegwitAddress.encode("bc", witnessVersion = 0, witnessProgram = program)

        assertEquals("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4".lowercase(), encoded)
    }

    @Test
    fun `encode-decode should round trip for a fresh 20-byte P2WPKH program`() {
        val program = "0011223344556677889900112233445566778899".fromHex()
        assertEquals(20, program.size)

        val address = SegwitAddress.encode("bc", witnessVersion = 0, witnessProgram = program)
        val decoded = SegwitAddress.decode("bc", address)

        assertEquals(0, decoded.witnessVersion)
        assertTrue(decoded.witnessProgram.contentEquals(program))
    }

    @Test
    fun `invalid checksum should be rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegwitAddress.decode("bc", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5")
        }
    }

    @Test
    fun `wrong hrp should be rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegwitAddress.decode("bc", "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7")
        }
    }

    @Test
    fun `invalid program length should be rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegwitAddress.decode("bc", "bc1rw5uspcuh")
        }
    }

    @Test
    fun `witness v0 with wrong program length per BIP141 should be rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegwitAddress.decode("bc", "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P")
        }
    }

    @Test
    fun `witness v0 encoded with Bech32m instead of Bech32 should be rejected (BIP350)`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegwitAddress.decode("bc", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh")
        }
    }
}
