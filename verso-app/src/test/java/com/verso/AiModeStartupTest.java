package com.verso;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.support.TestIdp;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The startup check is wired (ADR-0013; phase 6 review F3): a whole application whose model settings break the mode's
 * promise does not start, and says which setting to fix. AiModeCheckTest covers the rules one by one.
 */
class AiModeStartupTest {

    @Test
    void start_whenLocalModeIsGivenACloudProvider_fails() {
        assertThatThrownBy(() -> start("--VERSO_AI_MODE=local", "--VERSO_CHAT_PROVIDER=anthropic",
                "--spring.ai.anthropic.api-key=test-key"))
                .hasRootCauseMessage("verso.ai.mode=local needs spring.ai.model.chat=ollama; a cloud provider is only "
                        + "allowed with verso.ai.mode=cloud (ADR-0006)");
    }

    @Test
    void start_whenCloudModeHasNoKey_fails() {
        assertThatThrownBy(() -> start("--VERSO_AI_MODE=cloud", "--VERSO_CHAT_PROVIDER=anthropic"))
                .rootCause().hasMessageContaining("spring.ai.anthropic.api-key is missing");
    }

    private static void start(String... args) {
        new SpringApplicationBuilder(VersoApp.class)
                .initializers(new VersoPostgres.Initializer(), new TestIdp.Initializer(), new VersoTestEnvironment.Models())
                .run(concat(args, "--server.port=0", "--management.server.port=0")).close();
    }

    private static String[] concat(String[] a, String... b) {
        String[] all = new String[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
