package com.verso.qa.service.gateway;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The per-installation KEK from the secret file {@code /run/secrets/SECRET_MODEL_GATEWAY_KEK} (ADR-0056, ADR-0030
 * secrets rules): Spring's config tree turns the file into the setting of the same name, so there is no placeholder and
 * no literal fallback anywhere. The file holds at least 32 characters of random text ({@code scripts/dev-secrets.sh}
 * writes 40); the AES-256 key is derived from it with HKDF-SHA256, so the file's format does not limit the key.
 *
 * <p>Missing file: the store is "not set up" ({@link #available()} false) and only keyless connections work. A value
 * from an environment variable or a system property is refused at startup: it would show in {@code docker inspect} and
 * {@code /proc/<pid>/environ} (llm-rules 1.4). The KEK is held in this object only; it is never logged or stored.
 */
public final class LocalKeyEncryptionKey implements KeyEncryptionKey {

    public static final String SETTING = "SECRET_MODEL_GATEWAY_KEK";
    static final int MIN_CHARS = 32;
    private static final byte[] SALT = "verso-model-gateway".getBytes(StandardCharsets.UTF_8);

    private final byte[] kek;
    private final String id;

    private LocalKeyEncryptionKey(byte[] kek) {
        this.kek = kek;
        this.id = kek == null ? "none" : "local-" + HexFormat.of().formatHex(Arrays.copyOf(
                hmac(hkdf(kek, "kek-id"), "id".getBytes(StandardCharsets.UTF_8)), 4));
    }

    /** Reads the secret; throws when it is set in a forbidden place or too short (messages name the setting only). */
    public static LocalKeyEncryptionKey from(Environment environment) {
        String value = environment.getProperty(SETTING);
        if (value == null || value.isBlank()) return new LocalKeyEncryptionKey(null);
        if (environment instanceof ConfigurableEnvironment configurable && (definedIn(configurable,
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                || definedIn(configurable, StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME))) {
            throw new IllegalStateException(SETTING + " must come from /run/secrets, not from an environment variable or "
                    + "a system property (llm-rules 1.4)");
        }
        String trimmed = value.strip();
        if (trimmed.length() < MIN_CHARS) {
            throw new IllegalStateException(SETTING + " must hold at least " + MIN_CHARS + " random characters "
                    + "(scripts/dev-secrets.sh)");
        }
        return new LocalKeyEncryptionKey(hkdf(trimmed.getBytes(StandardCharsets.UTF_8), "kek-v1"));
    }

    /** For tests: a KEK from given secret text, or none. */
    static LocalKeyEncryptionKey ofSecret(String secret) {
        return new LocalKeyEncryptionKey(secret == null ? null : hkdf(secret.getBytes(StandardCharsets.UTF_8), "kek-v1"));
    }

    @Override
    public boolean available() {
        return kek != null;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public byte[] wrap(byte[] dataKey, byte[] context) {
        requireAvailable();
        return AesGcm.seal(kek, dataKey, withId(context, id));
    }

    @Override
    public byte[] unwrap(byte[] wrapped, String kekId, byte[] context) {
        requireAvailable();
        if (!id.equals(kekId)) throw new IllegalStateException("The data key was wrapped by another KEK");
        return AesGcm.open(kek, wrapped, withId(context, kekId));
    }

    @Override
    public byte[] derive(String purpose) {
        requireAvailable();
        return hkdf(kek, "derive:" + purpose);
    }

    @Override
    public String toString() {
        return "LocalKeyEncryptionKey[id=" + id + "]";
    }

    private void requireAvailable() {
        if (kek == null) throw new IllegalStateException(SETTING + " is not installed");
    }

    private static byte[] withId(byte[] context, String kekId) {
        byte[] suffix = ("|kek=" + kekId).getBytes(StandardCharsets.UTF_8);
        byte[] all = Arrays.copyOf(context, context.length + suffix.length);
        System.arraycopy(suffix, 0, all, context.length, suffix.length);
        return all;
    }

    /** HKDF-SHA256 (RFC 5869) with one output block: 32 bytes. */
    static byte[] hkdf(byte[] inputKey, String info) {
        byte[] prk = hmac(SALT, inputKey);
        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        byte[] block = Arrays.copyOf(infoBytes, infoBytes.length + 1);
        block[infoBytes.length] = 1;
        return hmac(prk, block);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable");
        }
    }

    private static boolean definedIn(ConfigurableEnvironment environment, String sourceName) {
        PropertySource<?> source = environment.getPropertySources().get(sourceName);
        if (!(source instanceof EnumerablePropertySource<?> enumerable)) return false;
        for (String name : enumerable.getPropertyNames()) {
            if (name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT).equals("SECRETMODELGATEWAYKEK")) return true;
        }
        return false;
    }
}
