package com.verso.qa.service.impl;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.CitationExtractor;
import com.verso.qa.service.CitationExtractor.Extracted;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.qa.service.PromptBuilder;
import com.verso.qa.service.PromptBuilder.BuiltPrompt;
import com.verso.qa.service.QaMetrics;
import com.verso.qa.service.QaMetrics.Outcome;
import com.verso.qa.service.QuestionService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * Question answering (ADR-0008): two remote calls on the hot path, no transaction, no retry.
 *
 * <ol>
 *   <li>Retrieval through document-api (embedding + vector search of the caller's READY documents).</li>
 *   <li>Below the similarity threshold the chat model is not asked: a fixed "not found" answer (llm-rules 3.4).</li>
 *   <li>Otherwise one chat call, bounded per instance (bulkhead) and by the HTTP client timeout; while the model is
 *       down a circuit breaker answers 503 at once.</li>
 *   <li>Citations are mapped on the server to the passages that were retrieved (3.3).</li>
 * </ol>
 *
 * Logs carry counts, sizes and durations; never the question, a passage, the prompt or the answer (2.1). A model
 * failure is a 503 with a fixed code; provider text never reaches the client or the log (2.3).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QuestionServiceImpl implements QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionServiceImpl.class);
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    private final DocumentRetrieval retrieval;
    private final ChatModel chatModel;
    private final PromptBuilder promptBuilder;
    private final CitationExtractor citations;
    private final QaProperties properties;
    private final VersoAiProperties ai;
    private final Semaphore chatSlots;
    private final ModelCircuitBreaker circuit;
    private final QaMetrics metrics;

    public QuestionServiceImpl(DocumentRetrieval retrieval, ChatModel chatModel, PromptBuilder promptBuilder,
                               CitationExtractor citations, QaProperties properties, VersoAiProperties ai,
                               ModelCircuitBreaker circuit, QaMetrics metrics) {
        this.retrieval = retrieval;
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.citations = citations;
        this.properties = properties;
        this.ai = ai;
        this.chatSlots = new Semaphore(properties.chatConcurrency());
        this.circuit = circuit;
        this.metrics = metrics;
    }

    @Override
    public AnswerResponse ask(AccountId account, String question) {
        // Before retrieval: an open circuit costs no embedding call either.
        if (circuit.isOpen()) {
            metrics.record(Outcome.CIRCUIT_OPEN);
            throw new QaServiceException(QaErrorCode.MODEL_UNAVAILABLE, "CIRCUIT_OPEN");
        }
        long started = System.nanoTime();
        List<RetrievedPassage> passages;
        try {
            passages = retrieval.search(account.value(), question, properties.topK());
        } catch (ServiceException e) {
            metrics.record(Outcome.RETRIEVAL_FAILED);
            throw e;
        }
        long retrievalMs = elapsedMs(started);
        metrics.retrievalTook(retrievalMs);
        List<RetrievedPassage> relevant = passages.stream()
                .filter(p -> p.similarity() >= properties.minSimilarity()).toList();
        if (relevant.isEmpty()) {
            log.info("Question answered: passages={} relevant=0 retrievalMs={} chatMs=0 totalMs={} outcome=not_found",
                    passages.size(), retrievalMs, elapsedMs(started));
            metrics.record(Outcome.NOT_FOUND);
            return new AnswerResponse(PromptBuilder.NOT_FOUND, false, List.of(), ai.mode().header(), ai.chatModel());
        }
        BuiltPrompt prompt = promptBuilder.build(question, relevant);
        long chatStarted = System.nanoTime();
        String raw = chat(prompt);
        long chatMs = elapsedMs(chatStarted);
        metrics.chatTook(chatMs);
        Extracted extracted = citations.extract(raw, relevant);
        boolean found = !extracted.citations().isEmpty();
        log.info("Question answered: passages={} relevant={} citations={} promptChars={} answerChars={} retrievalMs={} "
                        + "chatMs={} totalMs={} outcome={}", passages.size(), relevant.size(), extracted.citations().size(),
                prompt.system().length() + prompt.user().length(), extracted.answer().length(), retrievalMs, chatMs,
                elapsedMs(started), found ? "answered" : "uncited");
        metrics.record(found ? Outcome.ANSWERED : Outcome.UNCITED);
        return new AnswerResponse(extracted.answer(), found, extracted.citations(), ai.mode().header(), ai.chatModel());
    }

    private String chat(BuiltPrompt prompt) {
        boolean acquired;
        try {
            acquired = chatSlots.tryAcquire(properties.chatWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.record(Outcome.MODEL_BUSY);
            throw new QaServiceException(QaErrorCode.MODEL_BUSY, "INTERRUPTED");
        }
        if (!acquired) {
            metrics.record(Outcome.MODEL_BUSY);
            throw new QaServiceException(QaErrorCode.MODEL_BUSY, "NO_CHAT_SLOT");
        }
        // No runtime options: Spring AI 2.0 providers accept only their own options type, so temperature and the
        // answer length are provider settings (spring.ai.<provider>.chat.*, config/verso.yml).
        Prompt request = new Prompt(List.of(new SystemMessage(prompt.system()), new UserMessage(prompt.user())));
        Future<ChatResponse> call;
        // The slot is freed when the call really ends, not when the question gives up (bulkhead). Whoever claims
        // "started" owns the release: the task when it runs, the caller when it cancels a task that never started
        // (a cancelled FutureTask never runs its body, so its finally would never free the slot; phase 8 review RS1).
        AtomicBoolean started = new AtomicBoolean();
        try {
            call = VIRTUAL.submit(() -> {
                if (!started.compareAndSet(false, true)) return null;
                try {
                    return chatModel.call(request);
                } finally {
                    chatSlots.release();
                }
            });
        } catch (RuntimeException e) {
            chatSlots.release();
            throw failure(e.getClass().getSimpleName());
        }
        ChatResponse response;
        try {
            // One bound for every provider (ADR-0008: local 90 s, cloud 30 s), whatever the client's own timeout says.
            response = call.get(chatTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Interrupt the call: the Ollama client (Reactor Netty) cancels the request and Ollama stops generating
            // (measured). An abandoned generation kept a CPU host busy for minutes and starved every question after it
            // (phase 8, docs/capacity.md). The cloud SDKs (OkHttp) are interrupted too; that the provider stops is
            // not verified.
            cancel(call, started);
            throw failure("TIMEOUT");
        } catch (ExecutionException e) {
            // Provider exceptions can quote the prompt; only the type is kept (llm-rules 2.3).
            throw failure(e.getCause() == null ? "ExecutionException" : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            cancel(call, started);
            Thread.currentThread().interrupt();
            throw failure("INTERRUPTED");
        }
        String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) throw failure("EMPTY_ANSWER");
        circuit.recordSuccess();
        return text;
    }

    private void cancel(Future<?> call, AtomicBoolean started) {
        call.cancel(true);
        if (started.compareAndSet(false, true)) chatSlots.release();
    }

    private Duration chatTimeout() {
        return ai.mode() == AiMode.CLOUD ? properties.cloudChatTimeout() : properties.localChatTimeout();
    }

    private QaServiceException failure(String reason) {
        circuit.recordFailure();
        metrics.record(Outcome.MODEL_UNAVAILABLE);
        return new QaServiceException(QaErrorCode.MODEL_UNAVAILABLE, reason);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
