package com.verso.connector.api.enums;

/**
 * How a SharePoint connector proves its Entra app registration's identity (ADR-0045), OAuth 2.0 client credentials.
 * <ul>
 *   <li>{@code CERTIFICATE} (recommended): a signed client assertion (private_key_jwt); the secret file holds the private
 *       key and the certificate as PEM, only the certificate's public part is uploaded to Entra.</li>
 *   <li>{@code CLIENT_SECRET}: the app's client secret; simpler, but a shared secret that can be replayed if it leaks.</li>
 * </ul>
 */
public enum SharePointAuthMethod {
    CERTIFICATE,
    CLIENT_SECRET
}
