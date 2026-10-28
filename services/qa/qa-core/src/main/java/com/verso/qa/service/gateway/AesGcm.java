package com.verso.qa.service.gateway;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random 96-bit nonce and associated data (the key context, ADR-0056). The output is
 * {@code nonce || ciphertext || tag}. A wrong key, a changed byte or another context fails the tag check: the caller
 * gets an {@link IllegalStateException} without the JCE message.
 */
final class AesGcm {

    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    static byte[] randomKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    static byte[] seal(byte[] key, byte[] plaintext, byte[] context) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed: " + e.getClass().getSimpleName());
        }
    }

    static byte[] open(byte[] key, byte[] sealed, byte[] context) {
        if (sealed == null || sealed.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("AES-GCM ciphertext is malformed");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
            cipher.updateAAD(context);
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException: tampered, wrong key or wrong context. The JCE message says nothing useful.
            throw new IllegalStateException("AES-GCM decryption failed: " + e.getClass().getSimpleName());
        }
    }
}
