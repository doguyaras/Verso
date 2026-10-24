package com.verso.connector.sharepoint;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.verso.connector.smb.SourceException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * The certificate credential of the connector's Entra app registration (ADR-0045): a client assertion (RFC 7523,
 * "private_key_jwt") signed with the app's private key, instead of a client secret. Entra knows only the certificate's
 * public part; the private key stays in the secret file and in memory for one run.
 *
 * <p>The assertion: header {@code alg} RS256 (or PS256), {@code typ} JWT, {@code x5t#S256} and {@code x5t} (the
 * certificate's SHA-256 and SHA-1 thumbprints); claims {@code aud} the token endpoint, {@code iss} = {@code sub} the
 * client id, a random {@code jti}, {@code nbf} = {@code iat} now, {@code exp} five minutes later.
 */
final class ClientAssertion {

    static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MIN_KEY_BITS = 2048;

    private ClientAssertion() {}

    /** The private key and its certificate, read from one PEM secret file. */
    record Certificate(X509Certificate certificate, PrivateKey key) {
        @Override
        public String toString() {
            return "Certificate[redacted]";
        }
    }

    /**
     * Reads "-----BEGIN PRIVATE KEY-----" (PKCS#8, RSA, at least 2048 bits) and "-----BEGIN CERTIFICATE-----" from the
     * secret file's content; the certificate must belong to the key and be valid now.
     *
     * @throws SourceException fatal CREDENTIAL_INVALID or CREDENTIAL_EXPIRED; never the content or a parser message
     */
    static Certificate parse(char[] pem, Instant now) {
        byte[] keyBytes = null;
        byte[] certBytes = null;
        try {
            keyBytes = block(pem, "PRIVATE KEY");
            certBytes = block(pem, "CERTIFICATE");
            if (keyBytes == null || certBytes == null) throw SourceException.fatal("CREDENTIAL_INVALID");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certBytes));
            if (!(key instanceof RSAPrivateCrtKey rsa) || !(certificate.getPublicKey() instanceof RSAPublicKey pub)
                    || !rsa.getModulus().equals(pub.getModulus()) || pub.getModulus().bitLength() < MIN_KEY_BITS) {
                throw SourceException.fatal("CREDENTIAL_INVALID");
            }
            try {
                certificate.checkValidity(Date.from(now));
            } catch (CertificateException e) {
                throw new SourceException("CREDENTIAL_EXPIRED", true, e);
            }
            return new Certificate(certificate, key);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SourceException("CREDENTIAL_INVALID", true, e);
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /** The signed assertion for one token request. */
    @SuppressWarnings("deprecation") // x5t (SHA-1) is what older Entra validation reads next to x5t#S256
    static String sign(String clientId, URI tokenEndpoint, Certificate credential, String algorithm, Instant now) {
        try {
            byte[] der = credential.certificate().getEncoded();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.parse(algorithm))
                    .type(JOSEObjectType.JWT)
                    .x509CertSHA256Thumbprint(Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(der)))
                    .x509CertThumbprint(Base64URL.encode(MessageDigest.getInstance("SHA-1").digest(der)))
                    .build();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .audience(tokenEndpoint.toString())
                    .issuer(clientId)
                    .subject(clientId)
                    .jwtID(UUID.randomUUID().toString())
                    .notBeforeTime(Date.from(now))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(LIFETIME)))
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(credential.key()));
            return jwt.serialize();
        } catch (JOSEException | GeneralSecurityException e) {
            throw new SourceException("CREDENTIAL_INVALID", true, e);
        }
    }

    /** The DER bytes of the first PEM block of this type, or null. */
    private static byte[] block(char[] pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = indexOf(pem, begin, 0);
        if (start < 0) return null;
        int from = start + begin.length();
        int stop = indexOf(pem, end, from);
        if (stop < 0) return null;
        char[] body = Arrays.copyOfRange(pem, from, stop);
        try {
            StringBuilder base64 = new StringBuilder(body.length);
            for (char c : body) if (!Character.isWhitespace(c)) base64.append(c);
            byte[] decoded = Base64.getDecoder().decode(base64.toString());
            base64.setLength(0);
            return decoded;
        } finally {
            Arrays.fill(body, '\0');
        }
    }

    private static int indexOf(char[] text, String needle, int from) {
        outer:
        for (int i = from; i <= text.length - needle.length(); i++) {
            for (int j = 0; j < needle.length(); j++) if (text[i + j] != needle.charAt(j)) continue outer;
            return i;
        }
        return -1;
    }
}
