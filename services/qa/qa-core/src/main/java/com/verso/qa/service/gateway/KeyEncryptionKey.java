package com.verso.qa.service.gateway;

/**
 * The key-encryption key (KEK) of the model gateway's key store (ADR-0056): it wraps and unwraps the per-connection
 * data keys (DEKs) and never leaves its backend. The KEK is never stored in the database; only its id is, next to each
 * wrapped DEK, so a new KEK can be introduced while old rows still name the one that wrapped them.
 *
 * <p>One implementation today, {@link LocalKeyEncryptionKey}: a per-installation secret file under /run/secrets. A
 * HashiCorp Vault Transit backend (wrap = {@code transit/encrypt}, unwrap = {@code transit/decrypt}, with the context
 * as Vault's key derivation context) is the planned second one; it fits this interface unchanged.
 */
public interface KeyEncryptionKey {

    /** False when no KEK is installed: no key can be stored or read (connections without a key still work). */
    boolean available();

    /** The id stored with every wrapped DEK; changes when the KEK changes. */
    String id();

    /**
     * Wraps a data key, bound to {@code context} (AES-GCM associated data): a wrapped key copied to another connection
     * row does not unwrap there.
     */
    byte[] wrap(byte[] dataKey, byte[] context);

    /** The data key, or an {@link IllegalStateException} when the KEK, the id or the context does not match. */
    byte[] unwrap(byte[] wrapped, String kekId, byte[] context);

    /**
     * A 256-bit key for another purpose, derived from the KEK and {@code purpose} (HKDF): the usage report's keyed
     * digest of team members (k-anonymity without storing who). Never the KEK itself.
     */
    byte[] derive(String purpose);
}
