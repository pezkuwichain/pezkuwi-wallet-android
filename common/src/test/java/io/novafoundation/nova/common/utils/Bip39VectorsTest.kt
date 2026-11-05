package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.encrypt.mnemonic.Mnemonic
import io.novasama.substrate_sdk_android.encrypt.mnemonic.MnemonicCreator
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The SDK's MnemonicCreator runs on the Apache-2.0 classes in io.github.novacrypto that replace the
// GPL-3.0 NovaCrypto libraries. A wrong word here would give users a phrase that restores another
// account, so every English vector of BIP-0039 (trezor/python-mnemonic vectors.json) is checked.
class Bip39VectorsTest {

    private val vectors = listOf(
        "00000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to "legal winner thank year wave sausage worth useful legal winner thank yellow",
        "80808080808080808080808080808080" to "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
        "ffffffffffffffffffffffffffffffff" to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
        "000000000000000000000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon agent",
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal will",
        "808080808080808080808080808080808080808080808080" to "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter always",
        "ffffffffffffffffffffffffffffffffffffffffffffffff" to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo when",
        "0000000000000000000000000000000000000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title",
        "8080808080808080808080808080808080808080808080808080808080808080" to "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless",
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
        "9e885d952ad362caeb4efe34a8e91bd2" to "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
        "6610b25967cdcca9d59875f5cb50b0ea75433311869e930b" to "gravity machine north sort system female filter attitude volume fold club stay feature office ecology stable narrow fog",
        "68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c" to "hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length",
        "c0ba5a8e914111210f2bd131f3d5e08d" to "scheme spot photo card baby mountain device kick cradle pact join borrow",
        "6d9be1ee6ebd27a258115aad99b7317b9c8d28b6d76431c3" to "horn tenant knee talent sponsor spell gate clip pulse soap slush warm silver nephew swap uncle crack brave",
        "9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863" to "panda eyebrow bullet gorilla call smoke muffin taste mesh discover soft ostrich alcohol speed nation flash devote level hobby quick inner drive ghost inside",
        "23db8160a31d3e0dca3688ed941adbf3" to "cat swing flag economy stadium alone churn speed unique patch report train",
        "8197a4a47f0425faeaa69deebc05ca29c0a5b5cc76ceacc0" to "light rule cinnamon wrap drastic word pride squirrel upgrade then income fatal apart sustain crack supply proud access",
        "066dca1a2bb7e8a1db2832148ce9933eea0f3ac9548d793112d9a95c9407efad" to "all hour make first leader extend hole alien behind guard gospel lava path output census museum junior mass reopen famous sing advance salt reform",
        "f30f8c1da665478f49b001d94c5fc452" to "vessel ladder alter error federal sibling chat ability sun glass valve picture",
        "c10ec20dc3cd9f652c7fac2f1230f7a3c828389a14392f05" to "scissors invite lock maple supreme raw rapid void congress muscle digital elegant little brisk hair mango congress clump",
        "f585c11aec520db57dd353c69554b21a89b20fb0650966fa0a9d6f74fd989d8f" to "void come effort suffer camp survey warrior heavy shoot primary clutch crush open amazing screen patrol group space point ten exist slush involve unfold"
    )

    @Test
    fun entropyGivesTheVectorMnemonic() {
        vectors.forEach { (entropy, words) ->
            assertEquals(words, MnemonicCreator.fromEntropy(entropy.fromHex()).words)
        }
    }

    @Test
    fun vectorMnemonicGivesBackItsEntropy() {
        vectors.forEach { (entropy, words) ->
            assertEquals(entropy, MnemonicCreator.fromWords(words).entropy.toHexString())
        }
    }

    @Test
    fun randomMnemonicHasTheRequestedLengthAndRoundTrips() {
        Mnemonic.Length.values().forEach { length ->
            val mnemonic = MnemonicCreator.randomMnemonic(length)

            // Each word carries 11 bits; a checksum bit is added per 32 bits of entropy.
            assertEquals(length.byteLength * 8 * 33 / 32 / 11, mnemonic.wordList.size)
            assertTrue(mnemonic.entropy.contentEquals(MnemonicCreator.fromWords(mnemonic.words).entropy))
        }
    }
}
