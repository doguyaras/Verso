package com.verso.qa.service.gateway;

import com.verso.qa.repository.SealedKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Envelope encryption of a connection's API key (ADR-0056): a fresh random data key (DEK) per key version encrypts the
 * secret with AES-256-GCM; the KEK wraps the DEK. Both are bound to the key context
 * {@code verso:model-connection:<id>:v<version>}: a ciphertext or a wrapped DEK copied to another row, or replayed as
 * another version, fails the tag check. The plaintext DEK lives only inside {@link #seal} and {@link #open} and is
 * wiped afterwards; the plaintext key is returned to the caller (the client factory) and never stored or logged.
 */
public final class EnvelopeCipher {

    private final KeyEncryptionKey kek;

    public EnvelopeCipher(KeyEncryptionKey kek) {
        this.kek = kek;
    }

    public boolean available() {
        return kek.available();
    }

    public SealedKey seal(String secret, UUID connectionId, int version) {
        byte[] context = context(connectionId, version);
        byte[] dek = AesGcm.randomKey();
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] ciphertext = AesGcm.seal(dek, plaintext, context);
            return new SealedKey(ciphertext, kek.wrap(dek, context), kek.id());
        } finally {
            Arrays.fill(dek, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** The secret, or an {@link IllegalStateException} (tampered, wrong row, wrong version, other KEK). */
    public String open(SealedKey sealed, UUID connectionId, int version) {
        byte[] context = context(connectionId, version);
        byte[] dek = kek.unwrap(sealed.wrappedDek(), sealed.kekId(), context);
        byte[] plaintext = null;
        try {
            plaintext = AesGcm.open(dek, sealed.ciphertext(), context);
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(dek, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    static byte[] context(UUID connectionId, int version) {
        return ("verso:model-connection:" + connectionId + ":v" + version).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The last four characters for the panel ({@code …a1b2}); nothing for a key shorter than 12, and a fixed word for a
     * service account JSON (its end is punctuation, its middle is the private key).
     */
    public static String hint(String secret) {
        String key = secret.strip();
        if (key.startsWith("{")) return "JSON";
        return key.length() < 12 ? "…" : "…" + key.substring(key.length() - 4);
    }
}
