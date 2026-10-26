package com.verso.qa.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.QuestionRequest;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.NamedInterface;

/** The contract module's rules (ADR-0007 #24): every package is the named interface "api"; values are records. */
class QaApiContractTest {

    @Test
    void packages_whenCompiled_areAllNamedInterfaceApi() throws Exception {
        Set<String> packages = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:com/verso/qa/api/**/*.class")) {
            String path = resource.getURL().toString();
            String relative = path.substring(path.indexOf("com/verso/qa/api"));
            packages.add(relative.substring(0, relative.lastIndexOf('/')).replace('/', '.'));
        }
        assertThat(packages).contains("com.verso.qa.api", "com.verso.qa.api.dto");
        for (String name : packages) {
            NamedInterface named = Class.forName(name + ".package-info").getAnnotation(NamedInterface.class);
            assertThat(named).as(name).isNotNull();
            assertThat(named.value()).as(name).containsExactly("api");
        }
    }

    @Test
    void types_whenExposed_areRecords() {
        for (Class<?> type : List.of(QuestionRequest.class, AnswerResponse.class, Citation.class,
                com.verso.qa.api.dto.ConversationSummary.class, com.verso.qa.api.dto.ConversationResponse.class,
                com.verso.qa.api.dto.TurnResponse.class, com.verso.qa.api.dto.HistoryCitation.class,
                com.verso.qa.api.dto.TurnFeedback.class, com.verso.qa.api.dto.FeedbackRequest.class,
                com.verso.qa.api.dto.RenameConversationRequest.class, com.verso.qa.api.dto.UsageMetricsResponse.class,
                // ADR-0035: the event payloads of the stream
                com.verso.qa.api.dto.StreamStageEvent.class, com.verso.qa.api.dto.StreamDeltaEvent.class,
                com.verso.qa.api.dto.StreamCitationsEvent.class, com.verso.qa.api.dto.StreamDoneEvent.class,
                com.verso.qa.api.dto.StreamErrorEvent.class,
                // ADR-0049: the answer memory
                com.verso.qa.api.dto.KnowledgeMemory.class, com.verso.qa.api.dto.KnowledgeCardRequest.class,
                com.verso.qa.api.dto.KnowledgeCardSummary.class, com.verso.qa.api.dto.KnowledgeCardResponse.class,
                com.verso.qa.api.dto.KnowledgeEvalRunResponse.class,
                // ADR-0053: skills (procedures)
                com.verso.qa.api.dto.SkillDraftRequest.class, com.verso.qa.api.dto.SkillUpdateRequest.class,
                com.verso.qa.api.dto.SkillSummary.class, com.verso.qa.api.dto.SkillResponse.class,
                com.verso.qa.api.dto.SkillVersionDiff.class, com.verso.qa.api.dto.SkillSuggestion.class,
                com.verso.qa.api.dto.ProcedureMatch.class, com.verso.qa.api.dto.SkillListing.class,
                com.verso.qa.api.dto.SkillDocument.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
        }
    }

    /** ADR-0023: the history fields come last, so the JSON of an answer only grows (additive change). */
    @Test
    void answerResponse_whenStored_keepsTheAnswerAndAddsItsIds() {
        java.util.UUID conversation = java.util.UUID.randomUUID();
        java.util.UUID turn = java.util.UUID.randomUUID();
        AnswerResponse answer = new AnswerResponse("a", true, com.verso.qa.api.dto.AnswerOutcome.ANSWERED, List.of(),
                "local", "m", null, null, "bağlamlı sorgu");
        AnswerResponse stored = answer.stored(conversation, turn);
        assertThat(stored.conversationId()).isEqualTo(conversation);
        assertThat(stored.turnId()).isEqualTo(turn);
        assertThat(stored.answer()).isEqualTo("a");
        assertThat(stored.contextQuery()).isEqualTo("bağlamlı sorgu");
        assertThat(stored.unverified()).as("the nine-argument constructor: no groundedness check").isNull();
        // ADR-0046 added contextQuery after them, ADR-0047 unverified after that: the JSON still only grows.
        var components = AnswerResponse.class.getRecordComponents();
        assertThat(components[components.length - 6].getName()).isEqualTo("conversationId");
        assertThat(components[components.length - 5].getName()).isEqualTo("turnId");
        assertThat(components[components.length - 4].getName()).isEqualTo("contextQuery");
        assertThat(components[components.length - 3].getName()).isEqualTo("unverified");
        // ADR-0049: the answer memory's field after all of them; ADR-0053: the matching procedure after that.
        assertThat(components[components.length - 2].getName()).isEqualTo("memory");
        assertThat(components[components.length - 1].getName()).isEqualTo("procedure");
        assertThat(stored.memory()).as("a model answer has no memory field").isNull();
        assertThat(stored.procedure()).as("no procedure unless one matched").isNull();
        var procedure = new com.verso.qa.api.dto.ProcedureMatch(java.util.UUID.randomUUID(), "izin-talebi", "d",
                List.of());
        assertThat(answer.withProcedure(procedure).stored(conversation, turn).procedure()).isEqualTo(procedure);
        assertThat(com.verso.qa.api.dto.StreamDoneEvent.of(answer.withProcedure(procedure)).procedure())
                .isEqualTo(procedure);
        var done = com.verso.qa.api.dto.StreamDoneEvent.class.getRecordComponents();
        assertThat(done[done.length - 3].getName()).isEqualTo("contextQuery");
        assertThat(done[done.length - 2].getName()).isEqualTo("memory");
        assertThat(done[done.length - 1].getName()).isEqualTo("procedure");
        var citations = com.verso.qa.api.dto.StreamCitationsEvent.class.getRecordComponents();
        assertThat(citations).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("answer", "citations", "unverified");
    }

    /** ADR-0047: stored keeps the unverified sentences; the stream event carries the same list as the answer. */
    @Test
    void answerResponse_whenChecked_carriesTheUnverifiedSentencesIntoStoredAndTheStreamEvent() {
        var unverified = List.of(new com.verso.qa.api.dto.UnverifiedSentence(0, 5, List.of(1)));
        AnswerResponse answer = new AnswerResponse("a [1].", true, com.verso.qa.api.dto.AnswerOutcome.ANSWERED, List.of(),
                "local", "m", null, null, null, unverified);
        assertThat(answer.stored(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()).unverified())
                .isEqualTo(unverified);
        assertThat(com.verso.qa.api.dto.StreamCitationsEvent.of(answer).unverified()).isEqualTo(unverified);
        assertThat(com.verso.qa.api.dto.UnverifiedSentence.class.isRecord()).isTrue();
    }

    /**
     * ADR-0046: the optional rewrite step comes first, the ADR-0035 stages keep their order after it; ADR-0049's
     * MEMORY_FOUND ends a stream right after retrieval started (no passages, no writing).
     */
    @Test
    void answerStage_whenListed_putsRewritingBeforeRetrieval() {
        assertThat(com.verso.qa.api.dto.AnswerStage.values()).extracting(Enum::name)
                .containsExactly("REWRITING", "RETRIEVING", "MEMORY_FOUND", "PASSAGES_FOUND", "WRITING");
    }

    /** The feedback rules a client can break before the server looks at the turn (ADR-0023). */
    @Test
    void feedbackRequest_whenReasonAndCitationDisagree_isInconsistent() {
        var up = com.verso.qa.api.dto.FeedbackRating.UP;
        var down = com.verso.qa.api.dto.FeedbackRating.DOWN;
        var wrong = com.verso.qa.api.dto.FeedbackReason.WRONG_SOURCE;
        var other = com.verso.qa.api.dto.FeedbackReason.OTHER;
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(up, null, null).isReasonOnlyWithDown()).isTrue();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(up, other, null).isReasonOnlyWithDown()).isFalse();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, wrong, 1).isCitationNumberOnlyWithWrongSource()).isTrue();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, wrong, null).isCitationNumberOnlyWithWrongSource()).isFalse();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, other, 2).isCitationNumberOnlyWithWrongSource()).isFalse();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, null, null).isCitationNumberOnlyWithWrongSource()).isTrue();
    }
}
