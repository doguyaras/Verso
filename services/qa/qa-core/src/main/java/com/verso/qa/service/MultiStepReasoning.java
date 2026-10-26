package com.verso.qa.service;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.DocumentSystemModel;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.dto.SystemDefinition;
import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.core.text.TurkishText;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.ReasoningTrace;
import com.verso.qa.api.dto.ReasoningTrace.ContradictionNote;
import com.verso.qa.api.dto.ReasoningTrace.ContradictionSource;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.QaReasoningProperties;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.ContradictionRepository.Candidate;
import com.verso.qa.service.AnswerabilityGate.Decision;
import com.verso.qa.service.PromptBuilder.BuiltPrompt;
import com.verso.qa.service.ReasoningGate.Reason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Bounded multi-step reasoning (ADR-0048 phase 5, ADR-0055), everything except the chat calls, which stay with the
 * question service on its chat slot and time bounds:
 *
 * <ol>
 *   <li>the gate ({@link ReasoningGate}, plus a system-model lookup of the question with the caller's principals);</li>
 *   <li>the decomposition prompt and its strict validation ({@link QuestionDecomposer});</li>
 *   <li>one search per sub-question, exactly like a question: hybrid search with the caller's {@link Reader} (access is
 *       part of the query, ADR-0028), then the answerability gate; no answer per sub-question;</li>
 *   <li>the merge: the relevant passages of each sub-question, shared out under a cap, each passage once, numbered in
 *       one list so the citations are validated as for any answer (llm-rules 3.3);</li>
 *   <li>the trace for the asker, the explicit "not found" sentences, and contradiction notes taken only from stored
 *       offline checks (the ADR-0052 scan table, the ADR-0054 definitions), never from the model.</li>
 * </ol>
 *
 * Logs carry counts and fixed words; never a question, a sub-question, a label or a passage (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MultiStepReasoning {

    private static final Logger log = LoggerFactory.getLogger(MultiStepReasoning.class);
    /** At most this many contradiction notes per answer. */
    static final int MAX_NOTES = 5;

    /** Why the gate fired and the system-model entities of the question, when they were looked up (null otherwise). */
    public record Trigger(Reason reason, List<SystemEntityDetail> entities) {

        static Trigger none() {
            return new Trigger(Reason.NONE, null);
        }

        @Override
        public String toString() {
            return "Trigger[reason=" + reason + "]";
        }
    }

    /** One sub-question's search: what came back and what the answerability gate lets into the prompt. */
    public record Step(String question, int retrieved, List<RetrievedPassage> relevant) {

        @Override
        public String toString() {
            return "Step[retrieved=" + retrieved + ", relevant=" + relevant.size() + "]";
        }
    }

    /**
     * The synthesis input: the prompt passages in citation order, for each passage the sub-questions (0-based) it
     * belongs to, and for each sub-question whether it found anything.
     */
    public record Merged(List<String> questions, List<RetrievedPassage> passages, List<List<Integer>> stepsOf,
                         List<Boolean> found, int retrieved) {

        public boolean anyFound() {
            return found.contains(Boolean.TRUE);
        }

        public long notFound() {
            return found.stream().filter(f -> !f).count();
        }

        @Override
        public String toString() {
            return "Merged[steps=" + questions.size() + ", passages=" + passages.size() + "]";
        }
    }

    private final QaReasoningProperties properties;
    private final QaProperties qa;
    private final QuestionDecomposer decomposer;
    private final DocumentRetrieval retrieval;
    private final DocumentSystemModel systemModel;
    private final AnswerabilityGate gate;
    private final ContradictionRepository contradictions;

    public MultiStepReasoning(QaReasoningProperties properties, QaProperties qa, QuestionDecomposer decomposer,
                              DocumentRetrieval retrieval, DocumentSystemModel systemModel, AnswerabilityGate gate,
                              ContradictionRepository contradictions) {
        this.properties = properties;
        this.qa = qa;
        this.decomposer = decomposer;
        this.retrieval = retrieval;
        this.systemModel = systemModel;
        this.gate = gate;
        this.contradictions = contradictions;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public QaReasoningProperties properties() {
        return properties;
    }

    /**
     * The gate: the lexical heuristics first; when they cannot decide, the SYSTEM and CODE entities the question names
     * in the documents the caller may read (one indexed read in the document module, no model call). A lookup that
     * fails counts as no entity.
     */
    public Trigger trigger(Reader reader, String question) {
        Reason lexical = ReasoningGate.lexical(question);
        if (lexical != Reason.NONE) return new Trigger(lexical, null);
        if (!properties.entityGate() || !ReasoningGate.worthLookingUp(question)) return Trigger.none();
        List<SystemEntityDetail> entities = lookup(reader, question);
        Reason reason = ReasoningGate.decide(question, entities);
        return new Trigger(reason, entities);
    }

    /** The decomposition prompt, with the labels of the systems and codes found in the question as a hint. */
    public BuiltPrompt decompositionPrompt(String question, Trigger trigger) {
        List<String> hints = new ArrayList<>();
        if (trigger.entities() != null) {
            for (SystemEntityDetail entity : trigger.entities()) {
                if (entity.type() == SystemEntityType.SYSTEM || entity.type() == SystemEntityType.CODE) {
                    hints.add(entity.label());
                }
            }
        }
        return decomposer.prompt(question, hints);
    }

    public Optional<List<String>> subQuestions(String output, String question) {
        return decomposer.parse(output, question);
    }

    /**
     * One sub-question searched like a question: the caller's reader (ADR-0028), the configured top-k, the
     * answerability gate. A document error propagates; the caller falls back to the single pass.
     */
    public Step search(Reader reader, String subQuestion) throws ServiceException {
        List<RetrievedPassage> passages = retrieval.search(reader, subQuestion, qa.topK());
        Decision decision = gate.decide(passages);
        List<RetrievedPassage> relevant = decision == Decision.NONE ? List.of() : gate.promptPassages(passages);
        return new Step(subQuestion, passages.size(), relevant);
    }

    /**
     * The passages of all sub-questions in one list: each sub-question brings at most its share
     * ({@link QaReasoningProperties#passagesFor}), in its own retrieval order; a passage two sub-questions found is
     * listed once, under the first, and remembered for both. Nothing that was not retrieved for this caller is added.
     */
    public Merged merge(List<Step> steps) {
        int share = properties.passagesFor(steps.size());
        Map<String, Integer> index = new HashMap<>();
        List<RetrievedPassage> passages = new ArrayList<>();
        List<List<Integer>> stepsOf = new ArrayList<>();
        List<Boolean> found = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        int retrieved = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            questions.add(step.question());
            retrieved += step.retrieved();
            found.add(!step.relevant().isEmpty());
            int taken = 0;
            for (RetrievedPassage passage : step.relevant()) {
                if (taken == share) break;
                taken++;
                String key = key(passage);
                Integer at = index.get(key);
                if (at == null) {
                    index.put(key, passages.size());
                    passages.add(passage);
                    stepsOf.add(new ArrayList<>(List.of(i)));
                } else if (!stepsOf.get(at).contains(i)) {
                    stepsOf.get(at).add(i);
                }
            }
        }
        List<List<Integer>> frozen = stepsOf.stream().map(List::copyOf).toList();
        return new Merged(List.copyOf(questions), List.copyOf(passages), frozen, List.copyOf(found), retrieved);
    }

    /**
     * The explicit statement for every sub-question the caller's documents held nothing for, appended to the answer
     * by the server (the model is told not to guess for them); empty when every sub-question found something.
     */
    public static String notFoundSentences(Merged merged) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < merged.questions().size(); i++) {
            if (merged.found().get(i)) continue;
            if (!text.isEmpty()) text.append('\n');
            text.append(notFoundSentence(merged.questions().get(i)));
        }
        return text.toString();
    }

    /** "“X” için belgelerde bilgi bulunamadı." for one sub-question, without its question mark. */
    public static String notFoundSentence(String subQuestion) {
        String topic = subQuestion.strip();
        while (topic.endsWith("?") || topic.endsWith(".")) topic = topic.substring(0, topic.length() - 1).strip();
        return "“" + topic + "” için belgelerde bilgi bulunamadı.";
    }

    /** The trace for the asker: each sub-question, whether it found anything and which citations came from it. */
    public static ReasoningTrace trace(Merged merged, List<Citation> citations, List<ContradictionNote> notes) {
        List<ReasoningTrace.Step> steps = new ArrayList<>();
        for (int i = 0; i < merged.questions().size(); i++) {
            List<Integer> cited = new ArrayList<>();
            for (Citation citation : citations) {
                int passage = citation.number() - 1;
                if (passage >= 0 && passage < merged.stepsOf().size() && merged.stepsOf().get(passage).contains(i)) {
                    cited.add(citation.number());
                }
            }
            steps.add(new ReasoningTrace.Step(i + 1, merged.questions().get(i), merged.found().get(i), cited));
        }
        return new ReasoningTrace(steps, notes);
    }

    /**
     * Contradictions between two cited passages that Verso already knows from stored offline checks, by citation
     * number: a candidate of the contradiction scan (ADR-0052) whose two chunks are both cited and unchanged (the
     * stored SHA-256 equals the passage's), or two different definitions of the same term or code in the system model
     * (ADR-0054) whose two defining chunks are both cited and unchanged. The model is never asked. Best effort: a
     * failed read gives no note.
     */
    public List<ContradictionNote> contradictions(Reader reader, String question, Trigger trigger,
                                                  List<Citation> citations, List<RetrievedPassage> passages) {
        Map<UUID, Integer> cited = new LinkedHashMap<>();
        Map<UUID, String> hashes = new HashMap<>();
        for (Citation citation : citations) {
            int index = citation.number() - 1;
            if (index < 0 || index >= passages.size()) continue;
            RetrievedPassage passage = passages.get(index);
            if (passage.passageId() == null || cited.containsKey(passage.passageId())) continue;
            cited.put(passage.passageId(), citation.number());
            hashes.put(passage.passageId(), sha256(passage.content()));
        }
        if (cited.size() < 2) return List.of();
        Set<ContradictionNote> notes = new LinkedHashSet<>();
        try {
            for (Candidate candidate : contradictions.between(cited.keySet())) {
                UUID a = candidate.first().chunkId();
                UUID b = candidate.second().chunkId();
                if (candidate.first().hash().equals(hashes.get(a)) && candidate.second().hash().equals(hashes.get(b))) {
                    notes.add(note(cited.get(a), cited.get(b), ContradictionSource.CONTRADICTION_SCAN));
                }
            }
        } catch (DataAccessException e) {
            log.warn("Reasoning contradiction read failed: source=scan exceptionType={}", e.getClass().getSimpleName());
        }
        List<SystemEntityDetail> entities = trigger.entities() != null ? trigger.entities() : lookup(reader, question);
        for (SystemEntityDetail entity : entities) {
            if (entity.type() != SystemEntityType.TERM && entity.type() != SystemEntityType.CODE) continue;
            List<SystemDefinition> definitions = entity.definitions();
            for (int i = 0; i < definitions.size(); i++) {
                for (int j = i + 1; j < definitions.size(); j++) {
                    SystemDefinition x = definitions.get(i);
                    SystemDefinition y = definitions.get(j);
                    if (TurkishText.fold(x.value()).strip().equals(TurkishText.fold(y.value()).strip())) continue;
                    Integer first = citedAndUnchanged(x, cited, hashes);
                    Integer second = citedAndUnchanged(y, cited, hashes);
                    if (first != null && second != null && !first.equals(second)) {
                        notes.add(note(first, second, ContradictionSource.SYSTEM_MODEL));
                    }
                }
            }
        }
        return notes.stream().limit(MAX_NOTES).toList();
    }

    private static Integer citedAndUnchanged(SystemDefinition definition, Map<UUID, Integer> cited,
                                             Map<UUID, String> hashes) {
        UUID chunk = definition.source().passageId();
        if (chunk == null || !cited.containsKey(chunk)) return null;
        return hashes.get(chunk).equals(definition.source().contentSha256()) ? cited.get(chunk) : null;
    }

    private static ContradictionNote note(int a, int b, ContradictionSource source) {
        return new ContradictionNote(Math.min(a, b), Math.max(a, b), source);
    }

    private List<SystemEntityDetail> lookup(Reader reader, String question) {
        if (question == null || question.length() > DocumentSystemModel.MAX_QUERY_LENGTH) return List.of();
        try {
            return systemModel.lookup(reader, question, DocumentSystemModel.MAX_LOOKUP);
        } catch (DataAccessException | ServiceException e) {
            // Best effort: the gate and the notes work without the system model.
            log.warn("Reasoning system model lookup failed: exceptionType={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** The passage's identity: its chunk id, or where it comes from when a caller built it without one. */
    private static String key(RetrievedPassage passage) {
        return passage.passageId() != null ? passage.passageId().toString()
                : passage.documentId() + "#" + passage.pageNumber() + "#" + sha256(passage.content());
    }

    /** The same hash as the document module's chunk SHA-256 (hex of the UTF-8 content). */
    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }
}
