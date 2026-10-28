package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.api.dto.ModelConnectionRequest;
import com.verso.qa.api.dto.ModelConnectionResponse;
import com.verso.qa.api.dto.ModelKeyRequest;
import com.verso.qa.api.dto.ModelProviderType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0056: the API key is write-only: read from a request, never serialised, never in a string or a response. */
class ModelKeyJsonTest {

    private static final String KEY = "sk-" + "synthetic".repeat(2) + "-9z8y";
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void request_readsTheKey_butNeverWritesIt() {
        ModelConnectionRequest request = json.readValue("""
                {"name":"Test","type":"OPENAI","models":["gpt-x"],"apiKey":"%s","enabled":true}""".formatted(KEY),
                ModelConnectionRequest.class);

        assertThat(request.apiKey()).isEqualTo(KEY);
        assertThat(json.writeValueAsString(request)).doesNotContain(KEY).doesNotContain("apiKey");
        assertThat(request.toString()).doesNotContain(KEY).contains("apiKey=***");
    }

    @Test
    void rotation_readsTheKey_butNeverWritesIt() {
        ModelKeyRequest request = json.readValue("{\"apiKey\":\"" + KEY + "\"}", ModelKeyRequest.class);

        assertThat(request.apiKey()).isEqualTo(KEY);
        assertThat(json.writeValueAsString(request)).doesNotContain(KEY);
        assertThat(request.toString()).doesNotContain(KEY);
    }

    @Test
    void response_andRow_carryNoKeyMaterial() {
        EnvelopeCipher cipher = new EnvelopeCipher(LocalKeyEncryptionKey.ofSecret("z".repeat(40)));
        UUID id = UUID.randomUUID();
        ModelConnection row = new ModelConnection(id, "Test", ModelProviderType.OPENAI, null, null, null, null,
                List.of("gpt-x"), true, ModelConnection.Locality.EXTERNAL, "api.openai.com", cipher.seal(KEY, id, 1),
                EnvelopeCipher.hint(KEY), 1, Instant.now(), null, null, Instant.now(), Instant.now(), 1);

        ModelConnectionResponse response = row.toResponse();
        String body = json.writeValueAsString(response);

        assertThat(body).doesNotContain(KEY).doesNotContain("apiKey").doesNotContain("ciphertext")
                .contains("\"keyHint\":\"…9z8y\"").contains("\"keyStored\":true");
        assertThat(row.toString()).doesNotContain(KEY).doesNotContain("Sealed");
    }
}
