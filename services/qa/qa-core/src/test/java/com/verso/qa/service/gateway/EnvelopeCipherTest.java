package com.verso.qa.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.qa.repository.SealedKey;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/** ADR-0056: envelope encryption of provider keys. Round trip, tamper detection, key context, KEK handling. */
class EnvelopeCipherTest {

    private static final String KEK_TEXT = "k".repeat(20) + "synthetic-test-kek-0123";
    private static final String SECRET = "sk-test-SYNTHETIC-not-a-real-key-a1b2";
    private final EnvelopeCipher cipher = new EnvelopeCipher(LocalKeyEncryptionKey.ofSecret(KEK_TEXT));
    private final UUID id = UUID.randomUUID();

    @Test
    void seal_thenOpen_returnsTheSecret_andStoresNoPlaintext() {
        SealedKey sealed = cipher.seal(SECRET, id, 1);

        assertThat(cipher.open(sealed, id, 1)).isEqualTo(SECRET);
        assertThat(new String(sealed.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("SYNTHETIC");
        assertThat(sealed.wrappedDek()).hasSizeGreaterThan(32);
        assertThat(sealed.kekId()).startsWith("local-");
        assertThat(sealed.toString()).doesNotContain("SYNTHETIC").doesNotContain("[B@");
        // A fresh data key and nonce every time: the same secret never gives the same bytes.
        assertThat(cipher.seal(SECRET, id, 1).ciphertext()).isNotEqualTo(sealed.ciphertext());
    }

    @Test
    void open_whenCiphertextOrWrappedKeyIsTampered_fails() {
        SealedKey sealed = cipher.seal(SECRET, id, 1);
        byte[] ciphertext = sealed.ciphertext().clone();
        ciphertext[ciphertext.length - 1] ^= 1;
        byte[] dek = sealed.wrappedDek().clone();
        dek[15] ^= 1;

        assertThatThrownBy(() -> cipher.open(new SealedKey(ciphertext, sealed.wrappedDek(), sealed.kekId()),
                id, 1)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> cipher.open(new SealedKey(sealed.ciphertext(), dek, sealed.kekId()), id, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(new SealedKey(Arrays.copyOf(sealed.ciphertext(), 10),
                sealed.wrappedDek(), sealed.kekId()), id, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void open_withAnotherConnectionOrVersion_fails() {
        SealedKey sealed = cipher.seal(SECRET, id, 1);

        assertThatThrownBy(() -> cipher.open(sealed, UUID.randomUUID(), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(sealed, id, 2)).as("a replayed old version")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void open_withAnotherKek_fails() {
        SealedKey sealed = cipher.seal(SECRET, id, 1);
        EnvelopeCipher other = new EnvelopeCipher(LocalKeyEncryptionKey.ofSecret("x".repeat(40)));

        assertThatThrownBy(() -> other.open(sealed, id, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> other.open(new SealedKey(sealed.ciphertext(), sealed.wrappedDek(),
                other.seal("y", id, 1).kekId()), id, 1)).as("same id claimed, other key").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutKek_nothingCanBeSealed() {
        EnvelopeCipher none = new EnvelopeCipher(LocalKeyEncryptionKey.ofSecret(null));

        assertThat(none.available()).isFalse();
        assertThatThrownBy(() -> none.seal(SECRET, id, 1)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRET_MODEL_GATEWAY_KEK");
    }

    @Test
    void kek_fromConfigTreeProperty_isRead_butRefusedFromEnvironmentVariables_andWhenShort() {
        MockEnvironment fromTree = new MockEnvironment().withProperty("SECRET_MODEL_GATEWAY_KEK", KEK_TEXT);
        assertThat(LocalKeyEncryptionKey.from(fromTree).available()).isTrue();
        assertThat(LocalKeyEncryptionKey.from(new MockEnvironment()).available()).isFalse();

        StandardEnvironment fromEnv = new StandardEnvironment();
        fromEnv.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("SECRET_MODEL_GATEWAY_KEK", KEK_TEXT)));
        assertThatThrownBy(() -> LocalKeyEncryptionKey.from(fromEnv)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/run/secrets").hasMessageNotContaining(KEK_TEXT);

        MockEnvironment shortKek = new MockEnvironment().withProperty("SECRET_MODEL_GATEWAY_KEK", "short");
        assertThatThrownBy(() -> LocalKeyEncryptionKey.from(shortKek)).hasMessageNotContaining("short\"")
                .hasMessageContaining("at least 32");
        assertThat(LocalKeyEncryptionKey.from(fromTree).toString()).doesNotContain(KEK_TEXT);
    }

    @Test
    void derivedKeys_differByPurpose_andFromTheKek() {
        LocalKeyEncryptionKey kek = LocalKeyEncryptionKey.ofSecret(KEK_TEXT);

        assertThat(kek.derive("usage-reach")).hasSize(32).isNotEqualTo(kek.derive("other"));
        assertThat(kek.derive("usage-reach")).isEqualTo(LocalKeyEncryptionKey.ofSecret(KEK_TEXT).derive("usage-reach"));
    }

    @Test
    void hint_showsTheLastFourCharactersOnly() {
        assertThat(EnvelopeCipher.hint(SECRET)).isEqualTo("…a1b2");
        assertThat(EnvelopeCipher.hint("short")).isEqualTo("…");
        assertThat(EnvelopeCipher.hint("{\"type\":\"service_account\"}")).isEqualTo("JSON");
    }
}
