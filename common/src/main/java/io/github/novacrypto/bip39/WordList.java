package io.github.novacrypto.bip39;

/**
 * A BIP39 word list.
 *
 * <p>The classes under {@code io.github.novacrypto} are written for this app under Apache-2.0. They
 * replace the GPL-3.0 NovaCrypto libraries that substrate-sdk-android depends on, and keep only the
 * binary interface its MnemonicCreator and EnglishWordList are compiled against, so the SDK runs
 * unchanged. The NovaCrypto artifacts are excluded in the root build.gradle.
 */
public interface WordList {

    /** The word at {@code index}, 0 to 2047. */
    String getWord(int index);

    /** The character the words of a mnemonic are joined with. */
    char getSpace();
}
