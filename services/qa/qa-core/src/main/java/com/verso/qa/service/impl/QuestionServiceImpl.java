package com.verso.qa.service.impl;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.observability.tracing.TraceIds;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerOutcome;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.AnswerStage;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.AnswerStream;
import com.verso.qa.service.AnswerabilityGate;
import com.verso.qa.service.AnswerabilityGate.Decision;
import com.verso.qa.service.CitationExtractor;
import com.verso.qa.service.CitationExtractor.Extracted;
import com.verso.qa.service.ConversationHistory;
import com.verso.qa.service.ConversationHistory.Timings;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.qa.service.PromptBuilder;
import com.verso.qa.service.PromptBuilder.BuiltPrompt;
import com.verso.qa.service.QaMetrics;
import com.verso.qa.service.QaMetrics.Outcome;
import com.verso.qa.service.QuestionService;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.Disposable;

/**
 * Question answering (ADR-0008): two remote calls on the hot path, no transaction, no retry.
 *
 * <ol>
 *   <li>Retrieval through document-api (embedding + hybrid search of the caller's READY documents, ADR-0022).</li>
 *   <li>When the {@link AnswerabilityGate} finds nothing that can answer, the chat model is not asked: a fixed "not
 *       found" answer (llm-rules 3.4, ADR-0022).</li>
 *   <li>Otherwise one chat call, bounded per instance (bulkhead) and by the HTTP client timeout; while the model is
 *       down a circuit breaker answers 503 at once.</li>
 *   <li>Citations are mapped on the server to the passages that were retrieved (3.3).</li>
 *   <li>The answer is stored in the caller's history after the model call (ADR-0023): a database write, not a remote
 *       call; a failed write loses the turn, not the answer. The history never enters the prompt.</li>
 * </ol>
 *
 * The streamed variant (ADR-0035) runs the same steps with the same bulkhead, circuit and time bound; only the chat
 * call is a token stream, and citations are still mapped once, on the whole text, at the end. A client that leaves
 * cancels the provider request and nothing is stored.
 *
 * Logs carry counts, sizes and durations; never the question, a passage, the prompt or the answer (2.1). A model
 * failure is a 503 with a fixed code; provider text never reaches the client or the log (2.3).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QuestionServiceImpl implements QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionServiceImpl.class);
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();
    /** How often the streaming thread looks at the clock and the client while the model is silent. */
    private static final long STREAM_POLL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Object END = new Object();
    private static final String ANSWERED = "Question answered";
    private static final String STREAMED = "Question streamed";

    private final DocumentRetrieval retrieval;
    private final ChatModel chatModel;
    private final PromptBuilder promptBuilder;
    private final CitationExtractor citations;
    private final QaProperties properties;
    private final VersoAiProperties ai;
    private final Semaphore chatSlots;
    private final ModelCircuitBreaker circuit;
    private final QaMetrics metrics;
    private final ConversationHistory history;
    private final AnswerabilityGate gate;
    private final CallerGroups groups;

    public QuestionServiceImpl(DocumentRetrieval retrieval, ChatModel chatModel, PromptBuilder promptBuilder,
                               CitationExtractor citations, QaProperties properties, VersoAiProperties ai,
                               ModelCircuitBreaker circuit, QaMetrics metrics, ConversationHistory history,
                               AnswerabilityGate gate, CallerGroups groups) {
        this.groups = groups;
        this.history = history;
        this.retrieval = retrieval;
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.citations = citations;
        this.properties = properties;
        this.ai = ai;
        // One semaphore for both endpoints: a streamed answer takes the same slot as a JSON one (ADR-0035).
        this.chatSlots = new Semaphore(properties.chatConcurrency());
        this.circuit = circuit;
        this.metrics = metrics;
        this.gate = gate;
    }

    @Override
    public AnswerResponse ask(AccountId account, String question, UUID conversationId) {
        refuseWhileCircuitOpen();
        long started = System.nanoTime();
        // A foreign, deleted or full conversation is refused before any model call (ADR-0023).
        if (conversationId != null) history.checkWritable(account, conversationId);
        Retrieved retrieved = retrieve(readerOf(account), question);
        Answered answered;
        if (retrieved.relevant().isEmpty()) {
            answered = notFound(retrieved, started, ANSWERED);
        } else {
            BuiltPrompt prompt = promptBuilder.build(question, retrieved.relevant());
            long chatStarted = System.nanoTime();
            String raw = chat(prompt);
            answered = cited(raw, prompt, retrieved, elapsedMs(chatStarted), started, ANSWERED);
        }
        return store(account, conversationId, question, answered, started);
    }

    @Override
    public void stream(AccountId account, String question, UUID conversationId, AnswerStream out) {
        refuseWhileCircuitOpen();
        long started = System.nanoTime();
        if (conversationId != null) history.checkWritable(account, conversationId);
        // Read on the request thread: the token's groups live in its security context (ADR-0028).
        Reader reader = readerOf(account);
        VIRTUAL.execute(withCallerContext(() -> streamAnswer(account, reader, question, conversationId, out, started)));
    }

    private void streamAnswer(AccountId account, Reader reader, String question, UUID conversationId, AnswerStream out,
                              long started) {
        try {
            if (!out.stage(AnswerStage.RETRIEVING, 0, 0)) throw new Cancelled();
            Retrieved retrieved = retrieve(reader, question);
            // A client that left during retrieval costs no model call.
            if (!out.stage(AnswerStage.PASSAGES_FOUND, retrieved.passages().size(), retrieved.relevant().size())) {
                throw new Cancelled();
            }
            Answered answered;
            if (retrieved.relevant().isEmpty()) {
                answered = notFound(retrieved, started, STREAMED);
            } else {
                BuiltPrompt prompt = promptBuilder.build(question, retrieved.relevant());
                long chatStarted = System.nanoTime();
                String raw = streamChat(prompt, out);
                answered = cited(raw, prompt, retrieved, elapsedMs(chatStarted), started, STREAMED);
            }
            out.completed(store(account, conversationId, question, answered, started));
        } catch (Cancelled e) {
            // Nothing is stored: the text was never validated, and the user chose not to wait for it (ADR-0035).
            metrics.record(Outcome.CANCELLED);
            log.info("Question stream cancelled: totalMs={} outcome=cancelled", elapsedMs(started));
            out.cancelled();
        } catch (ServiceException e) {
            out.failed(e);
        } catch (RuntimeException e) {
            // Type only: a driver or framework message could quote the question (llm-rules 2.1).
            boolean database = e instanceof DataAccessException;
            log.error("Question stream failed: code={} reason=UNEXPECTED exceptionType={}",
                    database ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR", e.getClass().getSimpleName());
            out.failed(new ServiceException(database ? CommonErrorCode.SERVICE_UNAVAILABLE : CommonErrorCode.INTERNAL_ERROR,
                    "UNEXPECTED"));
        }
    }

    /** Retrieval's result: what came back, what the gate lets into the prompt, and how long it took. */
    private record Retrieved(List<RetrievedPassage> passages, List<RetrievedPassage> relevant, Decision decision,
                             long retrievalMs) {
    }

    /** The answer and where its time went (stored with the turn, ADR-0023). */
    private record Answered(AnswerResponse response, long retrievalMs, long chatMs) {
    }

    private Reader readerOf(AccountId account) {
        // The caller's sub and token groups (ADR-0028): only documents they reach can be retrieved, ranked or cited.
        return new Reader(account.value(), groups.current());
    }

    private void refuseWhileCircuitOpen() {
        // Before retrieval: an open circuit costs no embedding call either.
        if (circuit.isOpen()) {
            metrics.record(Outcome.CIRCUIT_OPEN);
            throw new QaServiceException(QaErrorCode.MODEL_UNAVAILABLE, "CIRCUIT_OPEN");
        }
    }

    private Retrieved retrieve(Reader reader, String question) {
        long retrievalStarted = System.nanoTime();
        List<RetrievedPassage> passages;
        try {
            passages = retrieval.search(reader, question, properties.topK());
        } catch (ServiceException e) {
            metrics.record(Outcome.RETRIEVAL_FAILED);
            throw e;
        }
        long retrievalMs = elapsedMs(retrievalStarted);
        metrics.retrievalTook(retrievalMs);
        Decision decision = gate.decide(passages);
        List<RetrievedPassage> relevant = decision == Decision.NONE ? List.of() : gate.promptPassages(passages);
        return new Retrieved(passages, relevant, decision, retrievalMs);
    }

    private Answered notFound(Retrieved retrieved, long started, String event) {
        log.info("{}: passages={} relevant=0 answerability=none retrievalMs={} chatMs=0 totalMs={} outcome=not_found",
                event, retrieved.passages().size(), retrieved.retrievalMs(), elapsedMs(started));
        metrics.record(Outcome.NOT_FOUND);
        return new Answered(new AnswerResponse(PromptBuilder.NOT_FOUND, false, AnswerOutcome.NOT_FOUND, List.of(),
                ai.mode().header(), ai.chatModel(), null, null), retrieved.retrievalMs(), 0);
    }

    /** Maps the markers of the whole model text to the retrieved passages (llm-rules 3.3), the same for both paths. */
    private Answered cited(String raw, BuiltPrompt prompt, Retrieved retrieved, long chatMs, long started, String event) {
        metrics.chatTook(chatMs);
        Extracted extracted = citations.extract(raw, retrieved.relevant());
        AnswerOutcome outcome = !extracted.citations().isEmpty() ? AnswerOutcome.ANSWERED
                : PromptBuilder.saysNotFound(extracted.answer()) ? AnswerOutcome.NOT_FOUND : AnswerOutcome.UNCITED;
        log.info("{}: passages={} relevant={} answerability={} citations={} promptChars={} answerChars={} "
                        + "retrievalMs={} chatMs={} totalMs={} outcome={}", event, retrieved.passages().size(),
                retrieved.relevant().size(), retrieved.decision().name().toLowerCase(Locale.ROOT),
                extracted.citations().size(), prompt.system().length() + prompt.user().length(),
                extracted.answer().length(), retrieved.retrievalMs(), chatMs, elapsedMs(started),
                outcome.name().toLowerCase(Locale.ROOT));
        metrics.record(Outcome.valueOf(outcome.name()));
        return new Answered(new AnswerResponse(extracted.answer(), outcome == AnswerOutcome.ANSWERED, outcome,
                extracted.citations(), ai.mode().header(), ai.chatModel(), null, null), retrieved.retrievalMs(), chatMs);
    }

    /** After the answer, outside any model call: one short transaction in the qa schema (llm-rules 5.1). */
    private AnswerResponse store(AccountId account, UUID conversationId, String question, Answered answered,
                                 long started) {
        AnswerResponse answer = answered.response();
        Timings timings = new Timings(answered.retrievalMs(), answered.chatMs(), elapsedMs(started));
        return history.record(account, conversationId, question, answer, timings)
                .map(stored -> answer.stored(stored.conversationId(), stored.turnId()))
                .orElse(answer);
    }

    private void acquireSlot() {
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
    }

    /**
     * No runtime options: Spring AI 2.0 providers accept only their own options type, so temperature and the answer
     * length are provider settings (spring.ai.&lt;provider&gt;.chat.*, config/verso.yml).
     */
    private static Prompt request(BuiltPrompt prompt) {
        return new Prompt(List.of(new SystemMessage(prompt.system()), new UserMessage(prompt.user())));
    }

    private String chat(BuiltPrompt prompt) {
        acquireSlot();
        Prompt request = request(prompt);
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
        String text = textOf(response);
        if (text == null || text.isBlank()) throw failure("EMPTY_ANSWER");
        circuit.recordSuccess();
        return text;
    }

    /**
     * The chat call as a token stream (ADR-0035). The provider's tokens arrive on its own I/O thread and are only queued
     * there; this thread writes them to the client, so a slow client never blocks the provider's event loop.
     */
    private String streamChat(BuiltPrompt prompt, AnswerStream out) {
        acquireSlot();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) chatSlots.release();
        };
        if (!out.stage(AnswerStage.WRITING, 0, 0)) {
            release.run();
            throw new Cancelled();
        }
        BlockingQueue<Object> items = new LinkedBlockingQueue<>();
        Disposable call;
        try {
            // The slot is freed when the generation really ends: completed, failed or cancelled (bulkhead).
            call = chatModel.stream(request(prompt))
                    .doFinally(signal -> release.run())
                    .subscribe(chunk -> {
                        String text = textOf(chunk);
                        if (text != null && !text.isEmpty()) items.add(text);
                    }, error -> items.add(new Failed(error)), () -> items.add(END));
        } catch (RuntimeException e) {
            release.run();
            throw failure(e.getClass().getSimpleName());
        }
        try {
            return collect(items, out);
        } finally {
            // A no-op after the last token. Otherwise (client gone, timeout, failure) it cancels the subscription: the
            // Ollama client (Reactor Netty) closes the connection and Ollama stops generating, as with the timeout of
            // the JSON path; an abandoned generation would keep a CPU host busy for minutes (docs/capacity.md).
            call.dispose();
        }
    }

    /** Writes the queued tokens until the end, the time bound (ADR-0008) or the client leaving. */
    private String collect(BlockingQueue<Object> items, AnswerStream out) {
        StringBuilder raw = new StringBuilder();
        long deadline = System.nanoTime() + chatTimeout().toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw failure("TIMEOUT");
                Object item = items.poll(Math.min(left, STREAM_POLL_NANOS), TimeUnit.NANOSECONDS);
                if (item == null) {
                    // A silent model (a CPU host before its first token): a comment keeps proxies from timing out and
                    // shows whether the client is still there.
                    if (!out.keepAlive()) throw new Cancelled();
                } else if (item == END) {
                    break;
                } else if (item instanceof Failed failed) {
                    // Provider exceptions can quote the prompt; only the type is kept (llm-rules 2.3).
                    throw failure(failed.error().getClass().getSimpleName());
                } else {
                    String text = (String) item;
                    raw.append(text);
                    if (!out.delta(text)) throw new Cancelled();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("INTERRUPTED");
        }
        if (raw.toString().isBlank()) throw failure("EMPTY_ANSWER");
        circuit.recordSuccess();
        return raw.toString();
    }

    private static String textOf(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null ? null
                : response.getResult().getOutput().getText();
    }

    /**
     * The background thread of a stream carries the caller's authentication (the audit event names the actor,
     * ADR-0026) and the request's trace id (logs and the audit row), nothing else of the request.
     */
    private static Runnable withCallerContext(Runnable work) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        String traceId = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes web
                ? TraceIds.current(web.getRequest()) : null;
        Runnable inner = () -> {
            if (mdc != null) MDC.setContextMap(mdc);
            if (traceId != null && MDC.get(TraceIds.MDC_KEY) == null) MDC.put(TraceIds.MDC_KEY, traceId);
            try {
                work.run();
            } finally {
                MDC.clear();
            }
        };
        return new DelegatingSecurityContextRunnable(inner);
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

    /** A provider failure, queued for the streaming thread. */
    private record Failed(Throwable error) {
    }

    /** The client left; unwinds the streaming thread without a stack trace. */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }
}
