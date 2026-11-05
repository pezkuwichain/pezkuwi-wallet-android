package io.github.novacrypto.hashing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 from the platform. See {@link io.github.novacrypto.bip39.WordList}. */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
