package com.verso.qa.repository;

/**
 * An API key as {@code qa.model_connection} keeps it (ADR-0056): the AES-GCM ciphertext, the data key wrapped by the
 * key-encryption key, and that KEK's id. No plaintext, no KEK. {@link #toString()} names the KEK id only.
 */
public record SealedKey(byte[] ciphertext, byte[] wrappedDek, String kekId) {

    @Override
    public String toString() {
        return "SealedKey[kekId=" + kekId + "]";
    }
}
