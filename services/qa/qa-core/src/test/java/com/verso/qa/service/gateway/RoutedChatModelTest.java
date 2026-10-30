package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;
import com.verso.qa.service.ModelBudgetExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.qa.api.dto.ModelProviderType;
import com.verso.qa.api.dto.ModelTask;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import reactor.core.publisher.Flux;

/** ADR-0056: routing, fallback, budgets, the privacy hook and the usage record of one task's route. */
class RoutedChatModelTest {

    private static final Prompt PROMPT = new Prompt(List.of(new UserMessage("synthetic question")));

    /** Records what the route did; models and budgets are set per test. */
    static final class FakeHooks implements RoutedChatModel.Hooks {
        final Map<String, ChatModel> models = new HashMap<>();
        final Set<String> overBudget = new HashSet<>();
        final List<String> records = new ArrayList<>();
        final List<List<ModelRequestFilter.EgressCategory>> sent = new ArrayList<>();
        final AtomicReference<Integer> filtered = new AtomicReference<>(0);

        @Override
        public ChatModel client(RoutedChatModel.Candidate candidate) {
            return models.get(candidate.model());
        }

        @Override
        public boolean overBudget(RoutedChatModel.Candidate candidate, String team) {
            return overBudget.contains(candidate.model());
        }

        final List<ModelRequestFilter.Filtered> filters = new ArrayList<>();
        /** null: a counting pass-through; else the real filter under test. */
        ModelRequestFilter filter;

        @Override
        public ModelRequestFilter filter() {
            if (filter != null) return filter;
            ModelRequestFilter.Exchange inner = new ModelRequestFilter.PassThrough().open(null);
            return new ModelRequestFilter() {
                @Override
                public String policy() {
                    return "TEST";
                }

                @Override
                public Exchange open(Call call) {
                    return new Exchange() {
                        @Override
                        public Prompt beforeSend(Prompt prompt) {
                            filtered.set(filtered.get() + 1);
                            return prompt;
                        }

                        @Override
                        public ChatResponse afterReceive(ChatResponse response) {
                            return response;
                        }

                        @Override
                        public Flux<ChatResponse> afterReceive(Flux<ChatResponse> stream) {
                            return inner.afterReceive(stream);
                        }

                        @Override
                        public Filtered filtered() {
                            return new Filtered("TEST", Map.of());
                        }
                    };
                }
            };
        }

        @Override
        public void record(RoutedChatModel.Candidate candidate, ModelTask task, String detail, String team, String account,
                           String outcome, Usage usage, List<ModelRequestFilter.EgressCategory> categories,
                           ModelRequestFilter.Filtered applied) {
            records.add(candidate.model() + ":" + outcome + (usage == null ? "" : ":" + usage.getPromptTokens() + "/"
                    + usage.getCompletionTokens()));
            sent.add(categories);
            filters.add(applied);
        }
    }

    static ModelConnection connection(ModelConnection.Locality locality) {
        return new ModelConnection(UUID.randomUUID(), "c", locality == ModelConnection.Locality.LOCAL
                ? ModelProviderType.OPENAI_COMPATIBLE : ModelProviderType.OPENAI, null, null, null, null,
                List.of("m"), true, locality, "h", null, null, 0, null, null, null, Instant.now(), Instant.now(), 0);
    }

    static ChatResponse answer(String text, int prompt, int completion) {
        return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(prompt, completion)).build()).build();
    }

    static ChatModel failing(RuntimeException e) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw e;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(e);
            }
        };
    }

    static ChatModel answering(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return answer(text, 11, 3);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(answer(text.substring(0, 2), 0, 0), answer(text.substring(2), 11, 3));
            }
        };
    }

    private RoutedChatModel route(FakeHooks hooks, String... models) {
        List<RoutedChatModel.Candidate> candidates = new ArrayList<>();
        for (String m : models) {
            candidates.add(new RoutedChatModel.Candidate(m.startsWith("ext")
                    ? connection(ModelConnection.Locality.EXTERNAL) : connection(ModelConnection.Locality.LOCAL), m));
        }
        return new RoutedChatModel(hooks, ModelTask.ANSWER, null, candidates, "team-a", "acct");
    }

    @Test
    void call_whenPrimaryAnswers5xxOr429_fallsBackInOrder_andCountsEveryCall() {
        FakeHooks hooks = new FakeHooks();
        hooks.models.put("ext-primary", failing(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)));
        hooks.models.put("ext-second", failing(new NonTransientAiException("429 - synthetic provider text")));
        hooks.models.put("local-third", answering("cevap"));

        ChatResponse response = route(hooks, "ext-primary", "ext-second", "local-third").call(PROMPT);

        assertThat(response.getResult().getOutput().getText()).isEqualTo("cevap");
        assertThat(hooks.records).containsExactly("ext-primary:FAILED", "ext-second:FAILED", "local-third:OK:11/3");
        // The egress log: the external attempts sent the answer task's kinds; the local one sent nothing out.
        assertThat(hooks.sent.get(0)).containsExactly(ModelRequestFilter.EgressCategory.SYSTEM_INSTRUCTIONS,
                ModelRequestFilter.EgressCategory.QUESTION, ModelRequestFilter.EgressCategory.PASSAGES);
        assertThat(hooks.sent.get(2)).isEmpty();
        assertThat(hooks.filtered.get()).as("the privacy hook ran around each external call only").isEqualTo(2);
    }

    @Test
    void call_whenPrimaryRefusesTheKey_stopsTheRoute() {
        FakeHooks hooks = new FakeHooks();
        HttpClientErrorException refused = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "x", null, null, null);
        hooks.models.put("ext-primary", failing(refused));
        hooks.models.put("local", answering("cevap"));

        assertThatThrownBy(() -> route(hooks, "ext-primary", "local").call(PROMPT)).isSameAs(refused);
        assertThat(hooks.records).containsExactly("ext-primary:FAILED");
    }

    @Test
    void call_skipsModelsOverTheirHardBudget_andBlocksWhenAllAre() {
        FakeHooks hooks = new FakeHooks();
        hooks.models.put("ext-paid", answering("pahalı"));
        hooks.models.put("local", answering("yerel"));
        hooks.overBudget.add("ext-paid");

        assertThat(route(hooks, "ext-paid", "local").call(PROMPT).getResult().getOutput().getText()).isEqualTo("yerel");
        assertThat(hooks.records).containsExactly("ext-paid:BLOCKED", "local:OK:11/3");

        hooks.overBudget.add("local");
        assertThatThrownBy(() -> route(hooks, "ext-paid", "local").call(PROMPT))
                .isInstanceOf(ModelBudgetExceededException.class).hasMessage(null);
    }

    /** llm-review L1: a call the caller gave up on (interrupt) never moves on to the next provider. */
    @Test
    void call_whenInterrupted_doesNotTryTheNextModel() {
        FakeHooks hooks = new FakeHooks();
        RuntimeException interrupted = new RuntimeException(new java.io.InterruptedIOException("timeout"));
        hooks.models.put("ext-a", failing(interrupted));
        hooks.models.put("ext-b", answering("x"));

        assertThatThrownBy(() -> route(hooks, "ext-a", "ext-b").call(PROMPT)).isSameAs(interrupted);
        assertThat(hooks.records).containsExactly("ext-a:FAILED");
        assertThat(Thread.interrupted()).as("the interrupt is restored (and cleared here)").isTrue();
    }

    @Test
    void call_whenTheLastModelFails_throwsItsFailure() {
        FakeHooks hooks = new FakeHooks();
        TransientAiException down = new TransientAiException("503 - synthetic");
        hooks.models.put("a", failing(down));

        assertThatThrownBy(() -> route(hooks, "a").call(PROMPT)).isSameAs(down);
    }

    @Test
    void stream_fallsBackBeforeTheFirstToken_andRecordsTheLastChunksUsage() {
        FakeHooks hooks = new FakeHooks();
        hooks.models.put("ext-down", failing(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)));
        hooks.models.put("local", answering("akış"));

        List<String> tokens = route(hooks, "ext-down", "local").stream(PROMPT)
                .map(r -> r.getResult().getOutput().getText()).collectList().block();

        assertThat(String.join("", tokens)).isEqualTo("akış");
        assertThat(hooks.records).containsExactly("ext-down:FAILED", "local:OK:11/3");
    }

    @Test
    void stream_neverSwitchesModelsAfterTheFirstToken() {
        FakeHooks hooks = new FakeHooks();
        hooks.models.put("half", new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.concat(Flux.just(answer("ya", 0, 0)),
                        Flux.error(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)));
            }
        });
        hooks.models.put("local", answering("akış"));

        assertThatThrownBy(() -> route(hooks, "half", "local").stream(PROMPT).collectList().block())
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(hooks.records).containsExactly("half:FAILED");
    }

    @Test
    void stream_whenEveryModelIsOverBudget_failsWithTheBudgetError() {
        FakeHooks hooks = new FakeHooks();
        hooks.models.put("ext", answering("x"));
        hooks.overBudget.add("ext");

        assertThatThrownBy(() -> route(hooks, "ext").stream(PROMPT).collectList().block())
                .isInstanceOf(ModelBudgetExceededException.class);
    }

    /** Remembers every prompt it was sent and streams {@code chunks} back (ADR-0059 round trips). */
    static final class EchoModel implements ChatModel {
        final List<String> sent = new ArrayList<>();
        final List<String> chunks;

        EchoModel(String... chunks) {
            this.chunks = List.of(chunks);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            sent.add(prompt.getContents());
            return answer(String.join("", chunks), 5, 2);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            sent.add(prompt.getContents());
            List<ChatResponse> out = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                out.add(answer(chunks.get(i), i == chunks.size() - 1 ? 5 : 0, i == chunks.size() - 1 ? 2 : 0));
            }
            return Flux.fromIterable(out);
        }
    }

    private static final Prompt PERSONAL = new Prompt(List.of(new UserMessage(
            "Müşterinin TC kimlik numarası 10000000146, e-postası ayse.yilmaz@ornek.com.tr; durumu nedir?")));

    @Test
    void stream_withMasking_restoresAPlaceholderSplitAcrossChunks_andRecordsCountsOnly() {
        FakeHooks hooks = new FakeHooks();
        hooks.filter = new PiiMaskingFilter();
        EchoModel cloud = new EchoModel("Kayıt [TC", "KN_", "1] için e-posta [EMAIL_1", "] doğrulandı. [");
        hooks.models.put("ext", cloud);

        String answer = String.join("", route(hooks, "ext").stream(PERSONAL)
                .map(r -> r.getResult().getOutput().getText()).collectList().block());

        assertThat(cloud.sent).singleElement().satisfies(sent -> assertThat(sent)
                .doesNotContain("10000000146", "ayse.yilmaz@ornek.com.tr").contains("[TCKN_1]", "[EMAIL_1]"));
        assertThat(answer).isEqualTo("Kayıt 10000000146 için e-posta ayse.yilmaz@ornek.com.tr doğrulandı. [");
        assertThat(hooks.records).as("the usage of the provider's last chunk, not the flush chunk")
                .containsExactly("ext:OK:5/2");
        assertThat(hooks.filters).singleElement().satisfies(f -> {
            assertThat(f.policy()).isEqualTo("PII_PSEUDONYMISE");
            assertThat(f.masked()).containsExactlyInAnyOrderEntriesOf(Map.of("TCKN", 1, "EMAIL", 1));
        });
    }

    /**
     * ADR-0065 and the ADR-0059 review: the built-in model of a cloud install (no connection, external) streams through
     * the filter too; a name placeholder and its suffix split over chunks come back as the name with a fitted suffix.
     */
    @Test
    void stream_onTheCloudBuiltInModel_masksNamesAndAddresses_andRestoresASplitSuffix() {
        FakeHooks hooks = new FakeHooks();
        hooks.filter = new PiiMaskingFilter();
        EchoModel builtIn = new EchoModel("Talep [AD", "_1]", "'i", "n; adres [ADRES_1]'d", "e.");
        hooks.models.put("builtin-chat", builtIn);
        RoutedChatModel route = new RoutedChatModel(hooks, ModelTask.ANSWER, null,
                List.of(new RoutedChatModel.Candidate(null, "builtin-chat", true)), "team-a", "acct");

        String answer = String.join("", route.stream(new Prompt(new UserMessage(
                        "Sn. Ayşe Yılmaz'ın adresi Kızılay Mah. Atatürk Bulvarı No:45 Çankaya/Ankara.")))
                .map(r -> r.getResult().getOutput().getText()).collectList().block());

        assertThat(builtIn.sent).singleElement().satisfies(sent -> assertThat(sent)
                .doesNotContain("Ayşe", "Yılmaz", "Kızılay", "Çankaya").contains("Sn. [AD_1]'ın adresi [ADRES_1]."));
        assertThat(answer).isEqualTo("Talep Ayşe Yılmaz'ın; adres Kızılay Mah. Atatürk Bulvarı No:45 "
                + "Çankaya/Ankara'da.");
        assertThat(hooks.sent.getFirst()).as("egress categories recorded").isNotEmpty();
        assertThat(hooks.filters).singleElement().satisfies(f -> assertThat(f.masked())
                .containsExactlyInAnyOrderEntriesOf(Map.of("AD", 1, "ADRES", 1)));
    }

    @Test
    void call_withMasking_onALocalModel_sendsThePromptUnchanged_andRecordsNoFilter() {
        FakeHooks hooks = new FakeHooks();
        hooks.filter = new PiiMaskingFilter();
        EchoModel local = new EchoModel("tamam");
        hooks.models.put("local", local);

        route(hooks, "local").call(PERSONAL);

        assertThat(local.sent).containsExactly(PERSONAL.getContents());
        assertThat(hooks.filters).containsExactly(ModelRequestFilter.Filtered.NONE);
    }

    @Test
    void call_whenTheFilterFails_sendsNothing_andDoesNotFallBack() {
        FakeHooks hooks = new FakeHooks();
        hooks.filter = new PiiMaskingFilter();
        EchoModel cloud = new EchoModel("x");
        EchoModel second = new EchoModel("y");
        hooks.models.put("ext-a", cloud);
        hooks.models.put("ext-b", second);
        // A tool response is a message kind the filter cannot rewrite: it must refuse, never send it unmasked.
        Prompt tool = new Prompt(List.of(new UserMessage("10000000146"),
                org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("1", "t",
                                "10000000146"))).build()));

        assertThatThrownBy(() -> route(hooks, "ext-a", "ext-b").call(tool)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("10000000146");
        assertThatThrownBy(() -> route(hooks, "ext-a").stream(tool).collectList().block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(cloud.sent).isEmpty();
        assertThat(second.sent).isEmpty();
        assertThat(hooks.records).containsExactly("ext-a:FAILED", "ext-a:FAILED");
    }

    @Test
    void classifier_readsStatusesNotMessages() {
        assertThat(FailureClassifier.classify(new NonTransientAiException("401 - key sk-x"))).isEqualTo(
                FailureClassifier.Kind.AUTH);
        assertThat(FailureClassifier.classify(new NonTransientAiException("404 - no model"))).isEqualTo(
                FailureClassifier.Kind.NOT_FOUND);
        assertThat(FailureClassifier.classify(new RuntimeException(new java.net.ConnectException("x"))))
                .isEqualTo(FailureClassifier.Kind.RETRYABLE);
        assertThat(FailureClassifier.classify(new IllegalStateException("x"))).isEqualTo(FailureClassifier.Kind.OTHER);
        assertThat(FailureClassifier.status(FailureClassifier.Kind.RETRYABLE,
                new NonTransientAiException("429 - slow down"))).isEqualTo("RATE_LIMITED");
    }
}
