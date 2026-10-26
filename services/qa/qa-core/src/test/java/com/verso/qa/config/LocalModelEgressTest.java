package com.verso.qa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * ADR-0006 decision 1.2, ADR-0051: in local mode the OpenAI-compatible client reaches internal addresses only; a public
 * one is refused before a byte is sent, and the message names neither the host nor the address.
 */
class LocalModelEgressTest {

    /** The customizer reaches the real client Spring AI builds (SpringAiOpenAiHttpClient): no connection is tried. */
    @Test
    void realClient_inLocalMode_refusesAPublicServerBeforeConnecting() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().baseUrl("http://203.0.113.9:8080/v1").apiKey("k").model("m")
                        .maxRetries(0).timeout(Duration.ofSeconds(30)).build())
                .httpClientBuilderCustomizer(QaConfiguration.LocalOpenAiEgressConfiguration
                        .localModelEgress(new VersoAiProperties(AiMode.LOCAL, "m", "e")))
                .build();
        long started = System.nanoTime();
        assertThatThrownBy(() -> model.call(new Prompt("soru"))).hasRootCauseMessage(
                "Local model server refused: an address is outside the internal network (verso.ai.mode=local, ADR-0006)");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("refused, not timed out").isLessThan(Duration.ofSeconds(10));
    }

    /** A model server never redirects; a redirect is refused and its answer is not used (security review, ADR-0051). */
    @Test
    void intercept_whenTheServerRedirects_refusesTheAnswer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Location", "/v1/elsewhere");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/v1/elsewhere", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new LocalModelEgress()).build();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            assertThatThrownBy(() -> client.newCall(new Request.Builder().url(url).build()).execute())
                    .isInstanceOf(IOException.class).hasMessageContaining("redirect");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requireInternal_whenTheServerIsOnTheInternalNetwork_letsTheRequestThrough() {
        for (String host : List.of("127.0.0.1", "localhost", "10.0.3.7", "172.18.0.4", "192.168.1.20", "::1")) {
            assertThatCode(() -> LocalModelEgress.requireInternal(host)).as(host).doesNotThrowAnyException();
        }
    }

    @Test
    void requireInternal_whenAnAddressIsPublic_refusesWithoutNamingIt() {
        for (String host : List.of("203.0.113.9", "8.8.8.8", "2001:db8::1")) {
            assertThatThrownBy(() -> LocalModelEgress.requireInternal(host)).as(host).isInstanceOf(IOException.class)
                    .hasMessageContaining("outside the internal network").hasMessageNotContaining(host);
        }
    }

    @Test
    void requireInternal_whenTheNameDoesNotResolve_refuses() {
        assertThatThrownBy(() -> LocalModelEgress.requireInternal("no-such-host.invalid"))
                .isInstanceOf(IOException.class).hasMessageContaining("does not resolve")
                .hasMessageNotContaining("no-such-host");
    }

    /** The interceptor sits in the client the OpenAI auto-configuration builds: a public address never connects. */
    @Test
    void customizer_inLocalMode_addsTheGuardThatFailsBeforeConnecting() {
        SpringAiOpenAiHttpClient.Builder builder = mock(SpringAiOpenAiHttpClient.Builder.class);
        QaConfiguration.LocalOpenAiEgressConfiguration.localModelEgress(new VersoAiProperties(AiMode.LOCAL, "m", "e"))
                .customize(builder);
        ArgumentCaptor<Interceptor> added = ArgumentCaptor.forClass(Interceptor.class);
        verify(builder).interceptor(added.capture());
        assertThat(added.getValue()).isInstanceOf(LocalModelEgress.class);

        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(added.getValue()).build();
        assertThatThrownBy(() -> client.newCall(new Request.Builder().url("http://203.0.113.9:8080/v1/chat/completions")
                .build()).execute()).isInstanceOf(IOException.class).hasMessageContaining("outside the internal network");
    }

    @Test
    void customizer_inCloudMode_addsNothing() {
        SpringAiOpenAiHttpClient.Builder builder = mock(SpringAiOpenAiHttpClient.Builder.class);
        QaConfiguration.LocalOpenAiEgressConfiguration.localModelEgress(new VersoAiProperties(AiMode.CLOUD, "m", "e"))
                .customize(builder);
        verifyNoInteractions(builder);
    }
}
