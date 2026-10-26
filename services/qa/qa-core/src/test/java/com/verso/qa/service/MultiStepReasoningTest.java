package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.DocumentSystemModel;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.dto.SystemDefinition;
import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.dto.SystemSource;
import com.verso.document.api.enums.SourceUnit;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.api.dto.ReasoningTrace;
import com.verso.qa.api.dto.ReasoningTrace.ContradictionNote;
import com.verso.qa.api.dto.ReasoningTrace.ContradictionSource;
import com.verso.qa.config.QaProperties;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.ContradictionRepository.Candidate;
import com.verso.qa.repository.ContradictionRepository.Side;
import com.verso.qa.service.MultiStepReasoning.Merged;
import com.verso.qa.service.MultiStepReasoning.Step;
import com.verso.qa.service.MultiStepReasoning.Trigger;
import com.verso.qa.service.ReasoningGate.Reason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * Everything of multi-step reasoning around the chat calls (ADR-0055): the per-sub-question search with the caller's
 * reader, the merge under the cap, the trace, the explicit "not found" sentence, and contradiction notes taken only
 * from stored offline checks.
 */
class MultiStepReasoningTest {

    private static final Reader READER = new Reader("acct-reasoning", List.of("grp-ops"));
    private final DocumentRetrieval retrieval = mock(DocumentRetrieval.class);
    private final DocumentSystemModel systemModel = mock(DocumentSystemModel.class);
    private final ContradictionRepository contradictions = mock(ContradictionRepository.class);
    private final QaProperties qa = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofMillis(300), 2,
            Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(2));
    private final MultiStepReasoning reasoning = new MultiStepReasoning(QuestionDecomposerTest.REASONING, qa,
            new QuestionDecomposer(QuestionDecomposerTest.REASONING), retrieval, systemModel, new AnswerabilityGate(qa),
            contradictions);

    @Test
    void search_whenASubQuestionIsSearched_usesTheCallersReaderAndTheAnswerabilityGate() {
        RetrievedPassage strong = passage("bt.pdf", 1, "P1 ilk yanıt 15 dakika", 0.9);
        RetrievedPassage weak = passage("bt.pdf", 2, "ilgisiz", 0.2);
        when(retrieval.search(eq(READER), eq("BT P1 ilk yanıt süresi nedir?"), anyInt()))
                .thenReturn(List.of(strong, weak));
        when(retrieval.search(eq(READER), eq("Kıdem tazminatı nasıl hesaplanır?"), anyInt()))
                .thenReturn(List.of(passage("x.pdf", 1, "ilgisiz", 0.1)));

        Step found = reasoning.search(READER, "BT P1 ilk yanıt süresi nedir?");
        Step none = reasoning.search(READER, "Kıdem tazminatı nasıl hesaplanır?");

        assertThat(found.retrieved()).isEqualTo(2);
        assertThat(found.relevant()).containsExactly(strong);
        assertThat(none.relevant()).as("the gate stops what cannot answer").isEmpty();
        // Access is part of the search: the sub-question goes with the caller's own reader, nothing else.
        verify(retrieval).search(eq(READER), eq("BT P1 ilk yanıt süresi nedir?"), eq(5));
        verify(retrieval, never()).search(any(Reader.class), anyString(), any(), anyInt());
    }

    @Test
    void merge_whenSubQuestionsShareAPassage_listsItOnceSharesTheCapAndRemembersBothSteps() {
        RetrievedPassage a1 = passage("bt.pdf", 1, "a1", 0.9);
        RetrievedPassage a2 = passage("bt.pdf", 2, "a2", 0.8);
        RetrievedPassage a3 = passage("bt.pdf", 3, "a3", 0.7);
        RetrievedPassage b1 = passage("destek.pdf", 1, "b1", 0.9);

        // Three sub-questions share six prompt passages: two each.
        Merged merged = reasoning.merge(List.of(new Step("s1", 5, List.of(a1, a2, a3)), new Step("s2", 5, List.of(a2,
                b1)), new Step("s3", 5, List.of())));

        assertThat(merged.passages()).containsExactly(a1, a2, b1);
        assertThat(merged.stepsOf()).containsExactly(List.of(0), List.of(0, 1), List.of(1));
        assertThat(merged.found()).containsExactly(true, true, false);
        assertThat(merged.retrieved()).isEqualTo(15);
        assertThat(merged.notFound()).isEqualTo(1);
        assertThat(merged.anyFound()).isTrue();
    }

    @Test
    void merge_whenTwoSubQuestions_givesEachThreePassages() {
        List<RetrievedPassage> four = List.of(passage("a.pdf", 1, "1", 0.9), passage("a.pdf", 2, "2", 0.9),
                passage("a.pdf", 3, "3", 0.9), passage("a.pdf", 4, "4", 0.9));
        Merged merged = reasoning.merge(List.of(new Step("s1", 4, four), new Step("s2", 0, List.of())));
        assertThat(merged.passages()).hasSize(3);
        assertThat(merged.anyFound()).isTrue();
    }

    @Test
    void notFoundSentences_whenASubQuestionFoundNothing_statesItExplicitly() {
        Merged merged = new Merged(List.of("BT P1 ilk yanıt süresi nedir?", "Kıdem tazminatı nasıl hesaplanır?"),
                List.of(passage("bt.pdf", 1, "x", 0.9)), List.of(List.of(0)), List.of(true, false), 6);

        assertThat(MultiStepReasoning.notFoundSentences(merged))
                .isEqualTo("“Kıdem tazminatı nasıl hesaplanır” için belgelerde bilgi bulunamadı.");
        assertThat(MultiStepReasoning.notFoundSentences(new Merged(List.of("a b c?"), List.of(), List.of(),
                List.of(true), 0))).isEmpty();
    }

    @Test
    void trace_whenCitationsComeFromBothGroups_mapsEachToItsSubQuestions() {
        Merged merged = new Merged(List.of("s1 soru nedir?", "s2 soru nedir?"),
                List.of(passage("a.pdf", 1, "a", 0.9), passage("a.pdf", 2, "b", 0.9), passage("b.pdf", 1, "c", 0.9)),
                List.of(List.of(0), List.of(0, 1), List.of(1)), List.of(true, true), 10);

        ReasoningTrace trace = MultiStepReasoning.trace(merged, List.of(citation(1), citation(2), citation(3)),
                List.of());

        assertThat(trace.steps()).extracting(ReasoningTrace.Step::citations)
                .containsExactly(List.of(1, 2), List.of(2, 3));
        assertThat(trace.steps()).extracting(ReasoningTrace.Step::number).containsExactly(1, 2);
        assertThat(trace.steps().getFirst().toString()).doesNotContain("s1 soru");
    }

    @Test
    void contradictions_whenTheScanStoredTheCitedPairUnchanged_notesItByCitationNumber() {
        RetrievedPassage a = passage("bt.pdf", 1, "P1 ilk yanıt 15 dakika", 0.9);
        RetrievedPassage b = passage("destek.pdf", 2, "P1 ilk yanıt 1 saat", 0.9);
        RetrievedPassage c = passage("izin.pdf", 1, "ilgisiz", 0.9);
        when(contradictions.between(any())).thenReturn(List.of(candidate(a, b, MultiStepReasoning.sha256(a.content()),
                MultiStepReasoning.sha256(b.content()))));

        List<ContradictionNote> notes = reasoning.contradictions(READER, "soru", new Trigger(Reason.COMPARISON,
                List.of()), List.of(citation(1), citation(3)), List.of(a, c, b));

        assertThat(notes).containsExactly(new ContradictionNote(1, 3, ContradictionSource.CONTRADICTION_SCAN));
    }

    @Test
    void contradictions_whenAStoredSideChangedOrIsNotCited_notesNothing() {
        RetrievedPassage a = passage("bt.pdf", 1, "P1 ilk yanıt 15 dakika", 0.9);
        RetrievedPassage b = passage("destek.pdf", 2, "P1 ilk yanıt 1 saat", 0.9);
        when(contradictions.between(any())).thenReturn(List.of(candidate(a, b, MultiStepReasoning.sha256(a.content()),
                "0".repeat(64))));
        Trigger trigger = new Trigger(Reason.COMPARISON, List.of());

        assertThat(reasoning.contradictions(READER, "soru", trigger, List.of(citation(1), citation(2)),
                List.of(a, b))).isEmpty();
        // One cited passage: nothing to compare, not even a read.
        assertThat(reasoning.contradictions(READER, "soru", trigger, List.of(citation(1)), List.of(a, b))).isEmpty();
        verify(contradictions, org.mockito.Mockito.times(1)).between(any());
    }

    @Test
    void contradictions_whenTheSystemModelHasTwoDefinitionsInTheCitedChunks_notesIt() {
        RetrievedPassage a = passage("depo.pdf", 1, "FEFO: First Expired First Out", 0.9);
        RetrievedPassage b = passage("erp.pdf", 4, "FEFO: First Entered First Out", 0.9);
        SystemEntityDetail fefo = new SystemEntityDetail(SystemEntityType.TERM, "FEFO", "FEFO", List.of(
                new SystemDefinition("First Expired First Out", source(a)),
                new SystemDefinition("First Entered First Out", source(b))), List.of(), List.of(), false);
        when(contradictions.between(any())).thenReturn(List.of());

        List<ContradictionNote> notes = reasoning.contradictions(READER, "soru", new Trigger(Reason.COMPARISON,
                List.of(fefo)), List.of(citation(2), citation(1)), List.of(a, b));

        assertThat(notes).containsExactly(new ContradictionNote(1, 2, ContradictionSource.SYSTEM_MODEL));
    }

    @Test
    void contradictions_whenTheSameDefinitionOrAFailedRead_notesNothing() {
        RetrievedPassage a = passage("depo.pdf", 1, "FEFO: First Expired First Out", 0.9);
        RetrievedPassage b = passage("erp.pdf", 4, "FEFO (first expired first out)", 0.9);
        SystemEntityDetail fefo = new SystemEntityDetail(SystemEntityType.TERM, "FEFO", "FEFO", List.of(
                new SystemDefinition("First Expired First Out", source(a)),
                new SystemDefinition("first expired first out", source(b))), List.of(), List.of(), false);
        when(contradictions.between(any())).thenThrow(new QueryTimeoutException("db"));
        // The lexical gate fired, so the system model is looked up only now, with the caller's reader.
        when(systemModel.lookup(eq(READER), eq("FEFO ile ilgili soru"), anyInt())).thenReturn(List.of(fefo));

        assertThat(reasoning.contradictions(READER, "FEFO ile ilgili soru", new Trigger(Reason.COMPARISON, null),
                List.of(citation(1), citation(2)), List.of(a, b))).isEmpty();
        verify(systemModel).lookup(eq(READER), eq("FEFO ile ilgili soru"), anyInt());
    }

    @Test
    void trigger_whenTheLexicalPartDecides_doesNotReadTheSystemModel() {
        assertThat(reasoning.trigger(READER, "BT ile müşteri destek arasındaki fark nedir?").reason())
                .isEqualTo(Reason.COMPARISON);
        verify(systemModel, never()).lookup(any(), anyString(), anyInt());
    }

    @Test
    void trigger_whenTheLexicalPartIsUndecided_countsTheCallersEntities() {
        String question = "ERP onay adımı WMS sevkiyat onayını etkiler mi?";
        when(systemModel.lookup(eq(READER), eq(question), anyInt())).thenReturn(List.of(
                new SystemEntityDetail(SystemEntityType.SYSTEM, "ERP", "ERP", List.of(), List.of(), List.of(), false),
                new SystemEntityDetail(SystemEntityType.SYSTEM, "WMS", "WMS", List.of(), List.of(), List.of(), false)));

        Trigger trigger = reasoning.trigger(READER, question);

        assertThat(trigger.reason()).isEqualTo(Reason.ENTITIES);
        assertThat(reasoning.decompositionPrompt(question, trigger).user())
                .contains("Soruda geçen sistemler ve kodlar: ERP, WMS");
        when(systemModel.lookup(any(), anyString(), anyInt())).thenThrow(new QueryTimeoutException("db"));
        assertThat(reasoning.trigger(READER, question).reason()).as("a failed lookup is no entity")
                .isEqualTo(Reason.NONE);
    }

    private static Candidate candidate(RetrievedPassage a, RetrievedPassage b, String hashA, String hashB) {
        return new Candidate(UUID.randomUUID(), new Side(a.passageId(), a.documentId(), a.pageNumber(), hashA),
                new Side(b.passageId(), b.documentId(), b.pageNumber(), hashB), 0.95, 1, Instant.now(), Instant.now());
    }

    private static SystemSource source(RetrievedPassage passage) {
        return new SystemSource(passage.documentId(), passage.fileName(), passage.pageNumber(), SourceUnit.PAGE,
                passage.passageId(), MultiStepReasoning.sha256(passage.content()), null, null, true);
    }

    static RetrievedPassage passage(String file, int page, String content, double similarity) {
        return new RetrievedPassage(UUID.nameUUIDFromBytes(file.getBytes()), file, page, SourceUnit.PAGE, content,
                similarity, 1.0, null, null, UUID.randomUUID());
    }

    private static Citation citation(int number) {
        return new Citation(number, UUID.randomUUID(), "f.pdf", 1, CitationUnit.PAGE, "e");
    }
}
