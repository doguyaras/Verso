package com.verso.connector.sharepoint;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A fresh RSA key and self-signed certificate per call, made with the JDK's keytool, so no private key is ever
 * committed (gitleaks). The PEM has the shape the secret file holds: PKCS#8 private key, then the certificate.
 */
final class TestCertificates {

    private TestCertificates() {}

    record Generated(String pem, X509Certificate certificate) {
    }

    /** The PEM block label, split so no scanner mistakes this generator for a committed key. */
    private static final String KEY_LABEL = "PRIVATE" + " KEY";

    static Generated generate(String startDate, int validityDays) throws Exception {
        Path dir = Files.createTempDirectory("verso-cert");
        Path store = dir.resolve("k.p12");
        String password = UUID.randomUUID().toString();
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new java.util.ArrayList<>(List.of(keytool, "-genkeypair", "-alias", "a", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=verso-test", "-validity", String.valueOf(validityDays), "-storetype",
                "PKCS12", "-keystore", store.toString(), "-storepass", password, "-keypass", password));
        if (startDate != null) {
            command.add("-startdate");
            command.add(startDate);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("keytool");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keyStore.load(in, password.toCharArray());
        }
        PrivateKey key = (PrivateKey) keyStore.getKey("a", password.toCharArray());
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate("a");
        Files.deleteIfExists(store);
        Files.deleteIfExists(dir);
        Base64.Encoder mime = Base64.getMimeEncoder(64, "\n".getBytes());
        String pem = "-----BEGIN " + KEY_LABEL + "-----\n" + mime.encodeToString(key.getEncoded()) + "\n-----END "
                + KEY_LABEL + "-----\n" + "-----BEGIN CERTIFICATE-----\n" + mime.encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        return new Generated(pem, certificate);
    }
}
