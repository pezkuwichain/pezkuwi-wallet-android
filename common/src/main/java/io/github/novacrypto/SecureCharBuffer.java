package io.github.novacrypto;

import java.io.Closeable;
import java.util.Arrays;

/**
 * A character buffer that is wiped when closed, for assembling a mnemonic. See
 * {@link io.github.novacrypto.bip39.WordList}.
 */
public final class SecureCharBuffer implements Closeable {

    private char[] chars = new char[256];
    private int length;

    public void append(CharSequence text) {
        if (length + text.length() > chars.length) {
            char[] grown = Arrays.copyOf(chars, Math.max(chars.length * 2, length + text.length()));
            Arrays.fill(chars, '\0');
            chars = grown;
        }
        for (int i = 0; i < text.length(); i++) {
            chars[length++] = text.charAt(i);
        }
    }

    /** The content so far; the caller owns the copy it makes from it. */
    public CharSequence toStringAble() {
        return new String(chars, 0, length);
    }

    @Override
    public void close() {
        Arrays.fill(chars, '\0');
        length = 0;
    }
}
