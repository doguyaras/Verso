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

        @Override
        public ModelRequestFilter filter() {
            return new ModelRequestFilter() {
                @Override
                public String policy() {
                    return "TEST";
                }

                @Override
                public Prompt beforeSend(Prompt prompt, Call call) {
                    filtered.set(filtered.get() + 1);
                    return prompt;
                }

                @Override
                public ChatResponse afterReceive(ChatResponse response, Call call) {
                    return response;
                }
            };
        }

        @Override
        public void record(RoutedChatModel.Candidate candidate, ModelTask task, String team, String account,
                           String outcome, Usage usage, List<ModelRequestFilter.EgressCategory> categories) {
            records.add(candidate.model() + ":" + outcome + (usage == null ? "" : ":" + usage.getPromptTokens() + "/"
                    + usage.getCompletionTokens()));
            sent.add(categories);
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
        return new RoutedChatModel(hooks, ModelTask.ANSWER, candidates, "team-a", "acct");
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
