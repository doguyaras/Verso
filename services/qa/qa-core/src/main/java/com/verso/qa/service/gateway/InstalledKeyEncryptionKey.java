package com.verso.qa.service.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The installation's key-encryption key (ADR-0056): {@link LocalKeyEncryptionKey} read at startup from
 * /run/secrets/SECRET_MODEL_GATEWAY_KEK (config tree). Set in an environment variable or a system property, or shorter
 * than 32 characters, the application does not start. Missing: keys cannot be stored; keyless connections and the
 * built-in model still work. A Vault Transit backend would be another implementation of the same interface.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InstalledKeyEncryptionKey implements KeyEncryptionKey {

    private final LocalKeyEncryptionKey delegate;

    public InstalledKeyEncryptionKey(Environment environment) {
        this.delegate = LocalKeyEncryptionKey.from(environment);
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public byte[] wrap(byte[] dataKey, byte[] context) {
        return delegate.wrap(dataKey, context);
    }

    @Override
    public byte[] unwrap(byte[] wrapped, String kekId, byte[] context) {
        return delegate.unwrap(wrapped, kekId, context);
    }

    @Override
    public byte[] derive(String purpose) {
        return delegate.derive(purpose);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
