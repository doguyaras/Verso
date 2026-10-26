package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.ProcedureMatch;
import com.verso.qa.api.dto.SkillOrigin;
import com.verso.qa.api.dto.SkillState;
import com.verso.qa.config.QaSkillProperties;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.repository.SkillRepository.Candidate;
import com.verso.qa.repository.SkillRepository.SkillRow;
import com.verso.qa.repository.SkillRepository.SourceRow;
import com.verso.qa.repository.SkillRepository.StepRow;
import com.verso.qa.repository.SkillRepository.VersionRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The trigger matching of the ask flow (ADR-0053): only APPROVED skills at or above the conservative threshold, only
 * for a caller whose own reading of every bound chunk is intact; nothing when the flag is off.
 */
class SkillMatcherTest {

    private static final Reader ALICE = new Reader("acct-alice", List.of("/hukuk"));
    private static final QuestionEmbedding QUESTION = new QuestionEmbedding("bge-m3", new float[] {1f, 0f});
    private static final UUID NEAR = UUID.randomUUID();
    private static final UUID NEARER = UUID.randomUUID();
    private static final SourcePage PAGE = new SourcePage(UUID.randomUUID(), 2);
    private static final String HASH = "e".repeat(64);

    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillContent content = mock(SkillContent.class);

    @Test
    void matching_keepsOnlyCandidatesAtOrAboveTheThreshold() {
        List<Candidate> candidates = List.of(new Candidate(NEARER, 0.95), new Candidate(NEAR, 0.90),
                new Candidate(UUID.randomUUID(), 0.8999), new Candidate(UUID.randomUUID(), Double.NaN));
        assertThat(SkillMatcher.matching(candidates, 0.90)).extracting(Candidate::skillId).containsExactly(NEARER, NEAR);
    }

    @Test
    void match_whenServingIsOff_touchesNothing() {
        assertThat(matcher(false).match(ALICE, QUESTION)).isEmpty();
        verifyNoInteractions(skills, content);
    }

    @Test
    void match_returnsTheFirstApprovedSkillWhoseSourcesTheCallerReadsUnchanged() {
        when(skills.nearestApproved(any(), eq("bge-m3"), eq(3)))
                .thenReturn(List.of(new Candidate(NEARER, 0.97), new Candidate(NEAR, 0.92)));
        SkillRow nearer = skill(NEARER, SkillState.APPROVED);
        SkillRow near = skill(NEAR, SkillState.APPROVED);
        when(skills.find(NEARER)).thenReturn(Optional.of(nearer));
        when(skills.find(NEAR)).thenReturn(Optional.of(near));
        SkillContent.Loaded loadedNearer = loaded(nearer);
        SkillContent.Loaded loadedNear = loaded(near);
        when(content.load(nearer)).thenReturn(Optional.of(loadedNearer));
        when(content.load(near)).thenReturn(Optional.of(loadedNear));
        // The nearer skill has a source this caller cannot read: its view is broken, as if it did not exist.
        when(content.view(ALICE, loadedNearer.sources())).thenReturn(new SkillContent.View(false, Map.of()));
        when(content.view(ALICE, loadedNear.sources())).thenReturn(new SkillContent.View(true, Map.of(PAGE,
                List.of(new SourceChunk(UUID.randomUUID(), PAGE.documentId(), 2, SourceUnit.PAGE, HASH, UUID.randomUUID(),
                        "izin.pdf", "İK", "metin")))));

        Optional<ProcedureMatch> match = matcher(true).match(ALICE, QUESTION);

        assertThat(match).isPresent();
        assertThat(match.get().skillId()).isEqualTo(NEAR);
        assertThat(match.get().name()).isEqualTo("izin-talebi");
        assertThat(match.get().steps()).singleElement().satisfies(step -> {
            assertThat(step.instruction()).isEqualTo("Formu doldurun.");
            assertThat(step.sources()).singleElement().satisfies(source -> {
                assertThat(source.page()).isEqualTo(2);
                assertThat(source.passageId()).as("a chunk with the bound text: the verso_fetch id").isNotNull();
            });
        });
    }

    @Test
    void match_neverShowsASkillThatIsNoLongerApproved_belowTheThreshold_orOnADatabaseFailure() {
        when(skills.nearestApproved(any(), anyString(), anyInt())).thenReturn(List.of(new Candidate(NEAR, 0.99)));
        when(skills.find(NEAR)).thenReturn(Optional.of(skill(NEAR, SkillState.STALE)));
        assertThat(matcher(true).match(ALICE, QUESTION)).isEmpty();
        verify(content, never()).view(any(), any());

        when(skills.nearestApproved(any(), anyString(), anyInt())).thenReturn(List.of(new Candidate(NEAR, 0.85)));
        assertThat(matcher(true).match(ALICE, QUESTION)).isEmpty();

        when(skills.nearestApproved(any(), anyString(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down: SELECT question FROM qa.skill_trigger"));
        assertThat(matcher(true).match(ALICE, QUESTION)).as("the box is dropped, the answer stands").isEmpty();
    }

    private SkillMatcher matcher(boolean serving) {
        return new SkillMatcher(skills, content, new QaSkillProperties(serving, 0.90, 3, 0.80, 400,
                Duration.ofSeconds(60), Duration.ofDays(365)), new SimpleMeterRegistry());
    }

    private static SkillRow skill(UUID id, SkillState state) {
        return new SkillRow(id, "izin-talebi", state, null, 1, SkillOrigin.MANUAL, null, List.of(UUID.randomUUID()),
                "acct-admin", 1, 1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }

    private static SkillContent.Loaded loaded(SkillRow skill) {
        return new SkillContent.Loaded(skill, new VersionRow(1, "izin-talebi", "İzin talebi prosedürü.", "DRAFTED", false,
                Instant.EPOCH), List.of(new StepRow(1, "Formu doldurun.", true)),
                List.of(new SourceRow(1, UUID.randomUUID(), PAGE, "PAGE", HASH)), List.of("İzin nasıl alınır?"),
                List.of());
    }
}
