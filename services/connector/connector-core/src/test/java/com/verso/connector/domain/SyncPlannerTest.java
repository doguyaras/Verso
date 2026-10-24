package com.verso.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.connector.domain.SyncPlanner.Fetch;
import com.verso.connector.domain.SyncPlanner.KnownItem;
import com.verso.connector.domain.SyncPlanner.KnownItem.State;
import com.verso.connector.domain.SyncPlanner.ListedFile;
import com.verso.connector.domain.SyncPlanner.Plan;
import com.verso.connector.domain.SyncPlanner.Reason;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The incremental diff of ADR-0038, without a file server. */
class SyncPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-20T08:00:00.123456Z");

    @Test
    void plan_whenNothingIsTracked_fetchesEveryListedFileAsNewInPathOrder() {
        Plan plan = SyncPlanner.plan(List.of(file("b.pdf", 2), file("a.pdf", 1)), Map.of(), Set.of(), true, NOW);

        assertThat(plan.fetch()).extracting(f -> f.file().path()).containsExactly("a.pdf", "b.pdf");
        assertThat(plan.fetch()).extracting(Fetch::reason).containsOnly(Reason.NEW);
        assertThat(plan.delete()).isEmpty();
        assertThat(plan.unchanged()).isZero();
    }

    @Test
    void plan_whenSizeTimeAndIdAreTheSame_downloadsNothing() {
        Map<String, KnownItem> known = known(item("a.pdf", 1, State.SYNCED, null));

        Plan plan = SyncPlanner.plan(List.of(file("a.pdf", 1)), known, Set.of(), true, NOW);

        assertThat(plan.fetch()).isEmpty();
        assertThat(plan.unchanged()).isEqualTo(1);
    }

    @Test
    void plan_whenAnyPartOfTheFingerprintDiffers_fetchesTheFileAsChanged() {
        KnownItem stored = item("a.pdf", 1, State.SYNCED, null);
        for (ListedFile changed : List.of(new ListedFile("a.pdf", 1, 11, T1), new ListedFile("a.pdf", 1, 10, T1.plusMillis(1)),
                new ListedFile("a.pdf", 2, 10, T1))) {
            Plan plan = SyncPlanner.plan(List.of(changed), known(stored), Set.of(), true, NOW);
            assertThat(plan.fetch()).singleElement().satisfies(f -> {
                assertThat(f.reason()).isEqualTo(Reason.CHANGED);
                assertThat(f.known()).isEqualTo(stored);
            });
        }
    }

    @Test
    void plan_whenAFailedFileIsDue_retriesIt_andWhenNotDue_countsItAsWaiting() {
        KnownItem due = item("due.pdf", 1, State.FAILED, NOW.minusSeconds(1));
        KnownItem later = item("later.pdf", 2, State.FAILED, NOW.plusSeconds(60));

        Plan plan = SyncPlanner.plan(List.of(file("due.pdf", 1), file("later.pdf", 2)), known(due, later), Set.of(), true,
                NOW);

        assertThat(plan.fetch()).extracting(f -> f.file().path() + ":" + f.reason()).containsExactly("due.pdf:RETRY");
        assertThat(plan.waiting()).isEqualTo(1);
    }

    @Test
    void plan_whenASkippedFileIsUnchanged_leavesItSkipped_untilItChanges() {
        KnownItem skipped = item("big.pdf", 1, State.SKIPPED, null);

        assertThat(SyncPlanner.plan(List.of(file("big.pdf", 1)), known(skipped), Set.of(), true, NOW).fetch()).isEmpty();
        assertThat(SyncPlanner.plan(List.of(new ListedFile("big.pdf", 1, 5, T1)), known(skipped), Set.of(), true, NOW)
                .fetch()).extracting(Fetch::reason).containsExactly(Reason.CHANGED);
    }

    @Test
    void plan_whenTheDocumentWasDeletedInVerso_fetchesTheFileAgain() {
        UUID document = UUID.randomUUID();
        KnownItem synced = new KnownItem("a.pdf", 1, 10, T1, "ab", document, State.SYNCED, null, 0);

        Plan plan = SyncPlanner.plan(List.of(file("a.pdf", 1)), known(synced), Set.of(document), true, NOW);

        assertThat(plan.fetch()).extracting(Fetch::reason).containsExactly(Reason.RESTORE);
    }

    @Test
    void plan_whenATrackedFileIsNoLongerListed_deletesIt_onlyWhenTheListingWasComplete() {
        Map<String, KnownItem> known = known(item("kept.pdf", 1, State.SYNCED, null), item("gone.pdf", 2, State.SYNCED, null),
                item("gone-failed.pdf", 3, State.FAILED, NOW.plusSeconds(60)));

        Plan complete = SyncPlanner.plan(List.of(file("kept.pdf", 1)), known, Set.of(), true, NOW);
        Plan cut = SyncPlanner.plan(List.of(file("kept.pdf", 1)), known, Set.of(), false, NOW);

        assertThat(complete.delete()).extracting(KnownItem::path).containsExactly("gone-failed.pdf", "gone.pdf");
        assertThat(cut.delete()).as("an unreadable folder must not read as deleted files").isEmpty();
    }

    @Test
    void plan_whenAPathIsListedTwice_handlesItOnce() {
        Plan plan = SyncPlanner.plan(List.of(file("a.pdf", 1), file("a.pdf", 1)), Map.of(), Set.of(), true, NOW);

        assertThat(plan.fetch()).hasSize(1);
    }

    @Test
    void plan_whenRunTwiceOnTheSameState_isTheSame() {
        Map<String, KnownItem> known = known(item("a.pdf", 1, State.SYNCED, null));
        List<ListedFile> listed = List.of(file("a.pdf", 1), file("b.pdf", 2));

        assertThat(SyncPlanner.plan(listed, known, Set.of(), true, NOW))
                .isEqualTo(SyncPlanner.plan(listed, known, Set.of(), true, NOW));
    }

    @Test
    void deletionAllowed_whenAboveTheShareAndTheMinimum_holdsDeletionsBackUnlessConfirmed() {
        assertThat(SyncPlanner.deletionAllowed(5, 10, 20, 20, false)).as("at most the minimum").isTrue();
        assertThat(SyncPlanner.deletionAllowed(21, 1000, 20, 20, false)).as("2.1% of the files").isTrue();
        assertThat(SyncPlanner.deletionAllowed(300, 1000, 20, 20, false)).as("30% of the files").isFalse();
        assertThat(SyncPlanner.deletionAllowed(300, 1000, 20, 20, true)).as("confirmed by an admin").isTrue();
        assertThat(SyncPlanner.deletionAllowed(1000, 1000, 100, 0, false)).as("safety switched off").isTrue();
        assertThat(SyncPlanner.deletionAllowed(200, 1000, 20, 20, false)).as("exactly the share").isTrue();
    }

    @Test
    void backoff_whenAttemptsGrow_doublesUpToTheMaximum() {
        Duration base = Duration.ofMinutes(5);
        Duration max = Duration.ofHours(24);

        assertThat(SyncPlanner.backoff(1, base, max)).isEqualTo(Duration.ofMinutes(5));
        assertThat(SyncPlanner.backoff(3, base, max)).isEqualTo(Duration.ofMinutes(20));
        assertThat(SyncPlanner.backoff(40, base, max)).isEqualTo(max);
        assertThat(SyncPlanner.backoff(0, base, max)).isEqualTo(base);
    }

    /**
     * ADR-0044: a restriction changes no version ("last modified" misses it, CONFCLOUD-41375); the planner sees it by
     * comparing the listing's restricted flag with how the item was left.
     */
    @Test
    void plan_whenOnlyTheRestrictionChanged_fetchesTheFileAgain_otherwiseLeavesItAlone() {
        KnownItem synced = item("pages/1", 1, State.SYNCED, null);
        KnownItem restrictedSkip = new KnownItem("pages/2", 2, 10, T1, null, null, State.SKIPPED, null, 0, "RESTRICTED");
        KnownItem otherSkip = new KnownItem("pages/3", 3, 10, T1, null, null, State.SKIPPED, null, 0, "TOO_LARGE");
        ListedFile nowRestricted = new ListedFile("pages/1", 1, 10, T1, true);
        ListedFile nowOpen = new ListedFile("pages/2", 2, 10, T1, false);
        ListedFile stillSkipped = new ListedFile("pages/3", 3, 10, T1, false);

        Plan plan = SyncPlanner.plan(List.of(nowRestricted, nowOpen, stillSkipped), known(synced, restrictedSkip, otherSkip),
                Set.of(), true, NOW);

        assertThat(plan.fetch()).extracting(f -> f.file().path(), Fetch::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple("pages/1", Reason.RESTRICTION_CHANGED),
                org.assertj.core.groups.Tuple.tuple("pages/2", Reason.RESTRICTION_CHANGED));
        assertThat(plan.unchanged()).isEqualTo(1);

        Plan steady = SyncPlanner.plan(List.of(new ListedFile("pages/2", 2, 10, T1, true)), known(restrictedSkip),
                Set.of(), true, NOW);
        assertThat(steady.fetch()).as("still restricted, already skipped").isEmpty();
        Plan fresh = SyncPlanner.plan(List.of(new ListedFile("pages/9", 9, 1, T1, true)), Map.of(), Set.of(), true, NOW);
        assertThat(fresh.fetch()).singleElement().satisfies(f -> assertThat(f.reason()).isEqualTo(Reason.NEW));
    }

    /** ADR-0044: an incremental listing is passed as "not complete" for deletions, so nothing unlisted is deleted. */
    @Test
    void plan_whenTheListingMayNotDelete_keepsUnlistedItems() {
        Plan plan = SyncPlanner.plan(List.of(), known(item("pages/1", 1, State.SYNCED, null)), Set.of(), false, NOW);

        assertThat(plan.delete()).isEmpty();
    }

    private static ListedFile file(String path, long id) {
        return new ListedFile(path, id, 10, T1);
    }

    private static KnownItem item(String path, long id, State state, Instant next) {
        return new KnownItem(path, id, 10, T1, "ab", null, state, next, state == State.FAILED ? 1 : 0);
    }

    private static Map<String, KnownItem> known(KnownItem... items) {
        Map<String, KnownItem> map = new HashMap<>();
        for (KnownItem item : items) map.put(item.path(), item);
        return map;
    }
}
