package io.github.novacrypto.bip39;

import io.github.novacrypto.hashing.Sha256;

/** Turns entropy into a BIP39 mnemonic, as specified in BIP-0039. See {@link WordList}. */
public final class MnemonicGenerator {

    /** Receives the mnemonic piece by piece, so it never has to sit in an immutable String. */
    public interface Target {
        void append(CharSequence text);
    }

    private static final int BITS_PER_WORD = 11;

    private final WordList wordList;

    public MnemonicGenerator(WordList wordList) {
        this.wordList = wordList;
    }

    /**
     * Appends the words for {@code entropy} (16 to 32 bytes, a multiple of 4) to {@code target}: the
     * entropy followed by the first length/32 bits of its SHA-256, read in groups of 11 bits.
     */
    public void createMnemonic(byte[] entropy, Target target) {
        if (entropy.length < 16 || entropy.length > 32 || entropy.length % 4 != 0) {
            throw new IllegalArgumentException("Entropy must be 16 to 32 bytes, a multiple of 4: " + entropy.length);
        }
        byte[] checksum = Sha256.sha256(entropy);
        int checksumBits = entropy.length * 8 / 32;
        int words = (entropy.length * 8 + checksumBits) / BITS_PER_WORD;
        String space = String.valueOf(wordList.getSpace());

        for (int word = 0; word < words; word++) {
            int index = 0;
            for (int bit = word * BITS_PER_WORD; bit < (word + 1) * BITS_PER_WORD; bit++) {
                index = (index << 1) | bitAt(entropy, checksum, bit);
            }
            if (word > 0) {
                target.append(space);
            }
            target.append(wordList.getWord(index));
        }
    }

    private static int bitAt(byte[] entropy, byte[] checksum, int bit) {
        int entropyBits = entropy.length * 8;
        byte[] source = bit < entropyBits ? entropy : checksum;
        int position = bit < entropyBits ? bit : bit - entropyBits;
        return (source[position / 8] >> (7 - position % 8)) & 1;
    }
}
