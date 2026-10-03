package com.verso.qa.service.impl;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.CitationExtractor;
import com.verso.qa.service.CitationExtractor.Extracted;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.qa.service.PromptBuilder;
import com.verso.qa.service.PromptBuilder.BuiltPrompt;
import com.verso.qa.service.QuestionService;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

    private final DocumentRetrieval retrieval;
    private final ChatModel chatModel;
    private final PromptBuilder promptBuilder;
    private final CitationExtractor citations;
    private final QaProperties properties;
    private final VersoAiProperties ai;
    private final Semaphore chatSlots;
    private final ModelCircuitBreaker circuit;

    public QuestionServiceImpl(DocumentRetrieval retrieval, ChatModel chatModel, PromptBuilder promptBuilder,
                               CitationExtractor citations, QaProperties properties, VersoAiProperties ai,
                               ModelCircuitBreaker circuit) {
        this.retrieval = retrieval;
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.citations = citations;
        this.properties = properties;
        this.ai = ai;
        this.chatSlots = new Semaphore(properties.chatConcurrency());
        this.circuit = circuit;
    }

    @Override
    public AnswerResponse ask(AccountId account, String question) {
        // Before retrieval: an open circuit costs no embedding call either.
        if (circuit.isOpen()) throw new QaServiceException(QaErrorCode.MODEL_UNAVAILABLE, "CIRCUIT_OPEN");
        long started = System.nanoTime();
        List<RetrievedPassage> passages = retrieval.search(account.value(), question, properties.topK());
        long retrievalMs = elapsedMs(started);
        List<RetrievedPassage> relevant = passages.stream()
                .filter(p -> p.similarity() >= properties.minSimilarity()).toList();
        if (relevant.isEmpty()) {
            log.info("Question answered: passages={} relevant=0 retrievalMs={} chatMs=0 totalMs={} outcome=not_found",
                    passages.size(), retrievalMs, elapsedMs(started));
            return new AnswerResponse(PromptBuilder.NOT_FOUND, false, List.of(), ai.mode().header(), ai.chatModel());
        }
        BuiltPrompt prompt = promptBuilder.build(question, relevant);
        long chatStarted = System.nanoTime();
        String raw = chat(prompt);
        long chatMs = elapsedMs(chatStarted);
        Extracted extracted = citations.extract(raw, relevant);
        boolean found = !extracted.citations().isEmpty();
        log.info("Question answered: passages={} relevant={} citations={} promptChars={} answerChars={} retrievalMs={} "
                        + "chatMs={} totalMs={} outcome={}", passages.size(), relevant.size(), extracted.citations().size(),
                prompt.system().length() + prompt.user().length(), extracted.answer().length(), retrievalMs, chatMs,
                elapsedMs(started), found ? "answered" : "uncited");
        return new AnswerResponse(extracted.answer(), found, extracted.citations(), ai.mode().header(), ai.chatModel());
    }

    private String chat(BuiltPrompt prompt) {
        boolean acquired;
        try {
            acquired = chatSlots.tryAcquire(properties.chatWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QaServiceException(QaErrorCode.MODEL_BUSY, "INTERRUPTED");
        }
        if (!acquired) throw new QaServiceException(QaErrorCode.MODEL_BUSY, "NO_CHAT_SLOT");
        try {
            // No runtime options: Spring AI 2.0 providers accept only their own options type, so temperature and the
            // answer length are provider settings (spring.ai.<provider>.chat.*, config/verso.yml).
            ChatResponse response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(prompt.system()), new UserMessage(prompt.user()))));
            String text = response == null || response.getResult() == null ? null
                    : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) throw new QaServiceException(QaErrorCode.MODEL_UNAVAILABLE, "EMPTY_ANSWER");
            circuit.recordSuccess();
            return text;
        } catch (QaServiceException e) {
            circuit.recordFailure();
            throw e;
        } catch (RuntimeException e) {
            circuit.recordFailure();
            // Provider exceptions can quote the prompt; only the type is kept (llm-rules 2.3).
            throw new QaServiceException(QaErrorCode.MODEL_UNAVAILABLE, e.getClass().getSimpleName());
        } finally {
            chatSlots.release();
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
