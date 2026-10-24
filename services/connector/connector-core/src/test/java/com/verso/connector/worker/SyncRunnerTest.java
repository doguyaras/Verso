package com.verso.connector.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.connector.outbox.ConnectorAudit;
import com.verso.connector.repository.ConnectorDatabase;
import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.confluence.ConfluenceSourceFactory;
import com.verso.connector.domain.SyncPlanner.ListedFile;
import com.verso.connector.repository.ConnectorItemRepository;
import com.verso.connector.repository.ConnectorItemRepository.ItemRow;
import com.verso.connector.repository.ConnectorRepository;
import com.verso.connector.repository.ConnectorRepository.Finish;
import com.verso.connector.repository.ConnectorRow;
import com.verso.connector.repository.SyncRunRepository;
import com.verso.connector.repository.SyncRunRepository.RunRow;
import com.verso.connector.repository.SyncRunRepository.Tally;
import com.verso.connector.smb.FileSource;
import com.verso.connector.smb.FileSourceFactory;
import com.verso.connector.smb.SourceException;
import com.verso.document.api.DocumentIngestion;
import com.verso.document.api.dto.IngestionRequest;
import com.verso.document.api.dto.IngestionResult;
import com.verso.document.api.dto.IngestionResult.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

/**
 * The run's orchestration (ADR-0038) with the repositories and the file server mocked: a failing file does not stop
 * the run, a failed source deletes nothing, an incomplete listing deletes nothing, an identical file is not re-ingested,
 * backpressure stops the downloads, the deletion safety holds mass deletions back, and nothing of the source (host,
 * share, path, file name, password) reaches the log.
 */
class SyncRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-10-20T08:00:00Z");
    private static final String SECRET_MARK = "pw-" + UUID.randomUUID();

    private final ConnectorRepository connectors = mock(ConnectorRepository.class);
    private final ConnectorItemRepository items = mock(ConnectorItemRepository.class);
    private final SyncRunRepository runs = mock(SyncRunRepository.class);
    private final FileSourceFactory sources = mock(FileSourceFactory.class);
    private final ConfluenceSourceFactory confluenceSources = mock(ConfluenceSourceFactory.class);
    private final DocumentIngestion documents = mock(DocumentIngestion.class);
    private final ConnectorAudit audit = mock(ConnectorAudit.class);
    private final ConnectorDatabase db = mock(ConnectorDatabase.class);
    private final UUID connectorId = UUID.randomUUID();
    private final UUID collectionId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();
    private FakeSource source;
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        when(db.transactionally(any())).thenAnswer(a -> a.<java.util.function.Supplier<?>>getArgument(0).get());
        when(runs.start(connectorId)).thenReturn(new RunRow(runId, NOW, null, "RUNNING", null, false, 0, 0, 0, 0, 0, 0, 0, 0, "FULL", null));
        when(connectors.finish(any(), any(), any(), any())).thenReturn(true);
        when(connectors.renew(any(), any(), any())).thenReturn(true);
        when(documents.collectionExists(collectionId)).thenReturn(true);
        when(documents.present(any(), any())).thenAnswer(a -> Set.copyOf(a.<java.util.Collection<UUID>>getArgument(1)));
        when(items.all(connectorId)).thenReturn(List.of());
        source = new FakeSource();
        when(sources.open(any())).thenReturn(source);
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
    }

    @Test
    void run_whenOneFileCannotBeRead_ingestsTheOthers_andRecordsTheFailureForARetry() {
        source.add("a.pdf", "first");
        source.add("b.pdf", null);
        source.add("c.pdf", "third");
        when(documents.submit(any())).thenAnswer(a -> new IngestionResult(Outcome.CREATED, UUID.randomUUID()));

        run(connector(false));

        verify(documents, times(2)).submit(any());
        verify(items).failed(eq(connectorId), eq("b.pdf"), anyLong(), anyLong(), any(), any(), any(), eq("READ_FAILED"),
                eq(1), eq(NOW.plus(Duration.ofMinutes(5))), eq(NOW));
        Tally tally = finishedRun("PARTIAL", "FILES_FAILED");
        assertThat(tally.added()).isEqualTo(2);
        assertThat(tally.failed()).isEqualTo(1);
        assertThat(finish().result()).as("the source was listed whole: the index is current").isEqualTo(Finish.Result.SUCCESS);
    }

    @Test
    void run_whenTheSourceIsNotAllowed_deletesAndIngestsNothing_andCountsAFailure() {
        when(sources.open(any())).thenThrow(SourceException.fatal("EGRESS_DENIED"));
        when(items.all(connectorId)).thenReturn(List.of(item("tracked.pdf", UUID.randomUUID(), "ab")));

        run(connector(false));

        verify(documents, never()).submit(any());
        verify(documents, never()).delete(any(), any(), any());
        finishedRun("FAILED", "EGRESS_DENIED");
        assertThat(finish().result()).isEqualTo(Finish.Result.FAILURE);
        assertThat(finish().nextRunIn()).as("first failure: base backoff").isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void run_whenTheListingIsIncomplete_deletesNothing_andIsNoSuccess() {
        source.add("kept.pdf", "kept");
        source.complete = false;
        UUID gone = UUID.randomUUID();
        when(items.all(connectorId)).thenReturn(List.of(item("kept.pdf", UUID.randomUUID(), sha("kept")),
                item("behind-an-unreadable-folder.pdf", gone, "ab")));

        run(connector(false));

        verify(documents, never()).delete(any(), any(), any());
        verify(items, never()).delete(any(), any());
        finishedRun("PARTIAL", "DIRECTORY_UNREADABLE");
        assertThat(finish().result()).isEqualTo(Finish.Result.FAILURE);
    }

    @Test
    void run_whenAFileWasRemovedAtTheSource_deletesItsDocument_thenTheItem() {
        UUID gone = UUID.randomUUID();
        when(items.all(connectorId)).thenReturn(List.of(item("gone.pdf", gone, "ab")));
        when(documents.delete(collectionId, gone, "SOURCE_DELETED")).thenReturn(true);

        run(connector(false));

        var order = org.mockito.Mockito.inOrder(documents, items);
        order.verify(documents).delete(collectionId, gone, "SOURCE_DELETED");
        order.verify(items).delete(connectorId, "gone.pdf");
        assertThat(finishedRun("SUCCEEDED", null).deleted()).isEqualTo(1);
    }

    @Test
    void run_whenAFileWasTouchedButIsIdentical_keepsItsDocument() {
        UUID document = UUID.randomUUID();
        source.add("a.pdf", "same");
        when(items.all(connectorId)).thenReturn(List.of(new ItemRow("a.pdf", 7, 4, MODIFIED.minusSeconds(60), sha("same"),
                document, "SYNCED", null, 0, null, NOW)));

        run(connector(false));

        verify(documents, never()).submit(any());
        verify(items).synced(eq(connectorId), eq("a.pdf"), anyLong(), anyLong(), eq(MODIFIED), eq(sha("same")),
                eq(document), eq(NOW));
    }

    @Test
    void run_whenAFileChanged_replacesItsDocumentInOneCall_withAKeyFromPathAndContent() {
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        source.add("a.pdf", "v2");
        when(items.all(connectorId)).thenReturn(List.of(new ItemRow("a.pdf", 7, 2, MODIFIED.minusSeconds(60), sha("v1"),
                old, "SYNCED", null, 0, null, NOW)));
        when(documents.submit(any())).thenReturn(new IngestionResult(Outcome.CREATED, fresh));

        run(connector(false));

        ArgumentCaptor<IngestionRequest> request = ArgumentCaptor.forClass(IngestionRequest.class);
        verify(documents).submit(request.capture());
        assertThat(request.getValue().replaces()).isEqualTo(old);
        assertThat(request.getValue().idempotencyKey()).isEqualTo(
                com.verso.connector.domain.SourceSettings.contentKey(connectorId, "a.pdf", sha("v2")));
        assertThat(request.getValue().sourceAccount()).isEqualTo("connector:" + connectorId);
        assertThat(finishedRun("SUCCEEDED", null).updated()).isEqualTo(1);
    }

    @Test
    void run_whenTheIngestionQueueIsFull_stopsDownloading_andComesBackSoon() {
        for (String name : List.of("a.pdf", "b.pdf", "c.pdf")) source.add(name, name);
        when(documents.submit(any())).thenReturn(IngestionResult.rejected(Outcome.QUEUE_FULL));

        run(connector(false));

        verify(documents, times(1)).submit(any());
        assertThat(source.reads).as("no download after the backlog was met").containsExactly("a.pdf");
        assertThat(finishedRun("PARTIAL", "BACKLOG").deferred()).isEqualTo(3);
        assertThat(finish().nextRunIn()).isEqualTo(Duration.ofMinutes(2));
        verify(items, never()).synced(any(), anyString(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void run_whenMostTrackedFilesVanished_holdsTheDeletionsBack_unlessConfirmed() {
        List<ItemRow> tracked = new ArrayList<>();
        for (int i = 0; i < 30; i++) tracked.add(item("f" + i + ".pdf", UUID.randomUUID(), "ab"));
        when(items.all(connectorId)).thenReturn(tracked);

        run(connector(false));

        verify(documents, never()).delete(any(), any(), any());
        Tally held = finishedRun("PARTIAL", "DELETION_SAFETY");
        assertThat(held.deferred()).isEqualTo(30);
        assertThat(held.pendingDeletions()).as("the exact number the admin confirms (ADR-0042)").isEqualTo(30);
        assertThat(held.listingMode()).isEqualTo("FULL");

        org.mockito.Mockito.clearInvocations(documents, runs, connectors, items);
        when(documents.delete(any(), any(), any())).thenReturn(true);
        run(connector(true));

        verify(documents, times(30)).delete(eq(collectionId), any(), eq("SOURCE_DELETED"));
        assertThat(finish().consumedMassDeletion()).isTrue();
    }

    @Test
    void run_whenAFileIsLargerThanTheLimit_skipsItWithoutDownloading() {
        source.add("big.pdf", "x".repeat(64));

        run(connector(false), 10);

        assertThat(source.reads).isEmpty();
        verify(items).skipped(eq(connectorId), eq("big.pdf"), anyLong(), anyLong(), any(), eq("TOO_LARGE"), eq(NOW));
    }

    @Test
    void run_whenItLogs_writesIdsAndCodesButNothingOfTheSource() {
        source.add("Gizli/Maaş listesi.pdf", "content");
        source.add("b.pdf", null);
        when(documents.submit(any())).thenReturn(new IngestionResult(Outcome.CREATED, UUID.randomUUID()));

        run(connector(false));
        when(sources.open(any())).thenThrow(new SourceException("SOURCE_AUTH_FAILED", true,
                new IllegalStateException("logon failure for svc-verso " + SECRET_MARK)));
        run(connector(false));

        String all = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).contains("connectorId=" + connectorId).contains("code=SOURCE_AUTH_FAILED")
                .doesNotContain("Maaş", "Gizli", "b.pdf", "fs01.corp", "Paylasim", "svc-verso", SECRET_MARK,
                        "SECRET_CONNECTOR");
        ArgumentCaptor<AuditEvent> recorded = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).appendSystem(recorded.capture());
        verify(audit, never()).append(any());
        assertThat(recorded.getAllValues()).extracting(e -> e.action().name())
                .containsOnly("CONNECTOR_SYNC_STARTED", "CONNECTOR_SYNC_FINISHED");
    }

    /** ADR-0044: a restricted page is not read; the version synced before the restriction leaves the collection. */
    @Test
    void run_whenAPageBecameRestricted_removesItsDocument_andSkipsItWithoutReading() {
        UUID document = UUID.randomUUID();
        source.add("pages/7", "secret page", true);
        when(items.all(connectorId)).thenReturn(List.of(new ItemRow("pages/7", 1, 4, MODIFIED, sha("secret page"),
                document, "SYNCED", null, 0, null, NOW)));
        when(documents.delete(collectionId, document, "SOURCE_RESTRICTED")).thenReturn(true);

        run(confluence(null, null));

        assertThat(source.reads).isEmpty();
        verify(documents).delete(collectionId, document, "SOURCE_RESTRICTED");
        verify(items).skipped(eq(connectorId), eq("pages/7"), anyLong(), anyLong(), any(), eq("RESTRICTED"), eq(NOW));
        verify(documents, never()).submit(any());
        assertThat(finishedRun("SUCCEEDED", null).skipped()).isEqualTo(1);
    }

    /**
     * ADR-0044: an incremental listing deletes nothing it did not list, counts as a success (the changes are applied),
     * moves the cursor to the newest change and does not record a full listing.
     */
    @Test
    void run_whenTheListingIsIncremental_deletesNothing_andMovesTheCursor() {
        Instant newest = MODIFIED.plusSeconds(600);
        source.add("pages/1", "changed", false);
        source.incremental = true;
        source.highWater = newest;
        when(items.all(connectorId)).thenReturn(List.of(item("pages/2", UUID.randomUUID(), "ab")));
        when(documents.submit(any())).thenReturn(new IngestionResult(Outcome.CREATED, UUID.randomUUID()));
        when(confluenceSources.open(any())).thenReturn(source);

        run(confluence(MODIFIED.minusSeconds(3600), NOW.minusSeconds(3600)));

        verify(documents, never()).delete(any(), any(), any());
        Tally tally = finishedRun("SUCCEEDED", null);
        assertThat(tally.listingMode()).isEqualTo("INCREMENTAL");
        assertThat(tally.added()).isEqualTo(1);
        assertThat(finish().result()).isEqualTo(Finish.Result.SUCCESS);
        verify(connectors).advance(connectorId, token, newest, null);
        ArgumentCaptor<com.verso.connector.confluence.ConfluenceRunSettings> opened =
                ArgumentCaptor.forClass(com.verso.connector.confluence.ConfluenceRunSettings.class);
        verify(confluenceSources).open(opened.capture());
        assertThat(opened.getValue().full()).as("the full listing ran an hour ago").isFalse();
        assertThat(opened.getValue().cursor()).isEqualTo(MODIFIED.minusSeconds(3600));
    }

    /** ADR-0044: a page that failed keeps the cursor behind it, so the next incremental listing returns it again. */
    @Test
    void run_whenAPageFails_theCursorStopsAtIt_andAFullListingIsRecordedOnlyWithoutHeldDeletions() {
        Instant failedAt = MODIFIED.minusSeconds(120);
        source.addAt("pages/1", null, failedAt);
        source.addAt("pages/2", "ok", MODIFIED);
        source.highWater = MODIFIED;
        when(documents.submit(any())).thenReturn(new IngestionResult(Outcome.CREATED, UUID.randomUUID()));

        run(confluence(null, null));

        ArgumentCaptor<com.verso.connector.confluence.ConfluenceRunSettings> opened =
                ArgumentCaptor.forClass(com.verso.connector.confluence.ConfluenceRunSettings.class);
        verify(confluenceSources).open(opened.capture());
        assertThat(opened.getValue().full()).as("no cursor yet: a full listing").isTrue();
        verify(connectors).advance(connectorId, token, failedAt, NOW);
        finishedRun("PARTIAL", "FILES_FAILED");
    }

    private ConnectorRow confluence(Instant cursor, Instant fullListingAt) {
        when(confluenceSources.open(any())).thenReturn(source);
        return new ConnectorRow(connectorId, "Wiki", "wiki.corp.local", 443, null, "", null, null,
                "SECRET_CONNECTOR_CONFLUENCE_TOKEN", collectionId, List.of(), List.of(), 1_000_000, 60, true, false, false,
                "admin", NOW.minusSeconds(7200), NOW, NOW, null, null, token, 0, null, null, null, ConnectorRow.CONFLUENCE,
                new ConnectorRow.Confluence("https://wiki.corp.local", "DATA_CENTER", List.of("HR"), null, null, false,
                        true, 50), cursor, fullListingAt);
    }

    private void run(ConnectorRow connector) {
        if (connector.isConfluence()) {
            SyncRunner runner = new SyncRunner(connectors, items, runs, sources, confluenceSources, documents,
                    properties(), new ConnectorMetrics(new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC)),
                    audit, db, Clock.fixed(NOW, ZoneOffset.UTC));
            runner.run(connector, token, () -> false);
            return;
        }
        run(connector, 1_000_000);
    }

    private void run(ConnectorRow connector, long maxFileBytes) {
        ConnectorRow sized = new ConnectorRow(connector.id(), connector.name(), connector.host(), connector.port(),
                connector.share(), connector.pathPrefix(), connector.domain(), connector.username(),
                connector.credentialRef(), connector.collectionId(), connector.includeGlobs(), connector.excludeGlobs(),
                maxFileBytes, connector.scheduleMinutes(), true, false, connector.massDeletionConfirmed(),
                connector.createdBy(), connector.createdAt(), connector.updatedAt(), connector.nextRunAt(), null, null,
                token, 0, null, null, null);
        SyncRunner runner = new SyncRunner(connectors, items, runs, sources, confluenceSources, documents, properties(),
                new ConnectorMetrics(new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC)), audit, db,
                Clock.fixed(NOW, ZoneOffset.UTC));
        runner.run(sized, token, () -> false);
    }

    private Tally finishedRun(String status, String code) {
        ArgumentCaptor<Tally> tally = ArgumentCaptor.forClass(Tally.class);
        verify(runs).finish(eq(runId), eq(status), eq(code), tally.capture());
        return tally.getValue();
    }

    private Finish finish() {
        ArgumentCaptor<Finish> finish = ArgumentCaptor.forClass(Finish.class);
        verify(connectors, org.mockito.Mockito.atLeastOnce()).finish(eq(connectorId), eq(token), any(), finish.capture());
        return finish.getValue();
    }

    private ConnectorRow connector(boolean confirmed) {
        return new ConnectorRow(connectorId, "Paylasim", "fs01.corp.local", 445, "Paylasim", "IK", null, "svc-verso",
                "SECRET_CONNECTOR_SMB_PASSWORD", collectionId, List.of(), List.of(), 1_000_000, 60, true, false,
                confirmed, "admin", NOW.minusSeconds(3600), NOW, NOW, null, null, token, 0, null, null, null);
    }

    private static ItemRow item(String path, UUID document, String sha) {
        return new ItemRow(path, 7, 4, MODIFIED, sha.length() == 64 ? sha : sha("x" + sha), document, "SYNCED", null, 0,
                null, NOW);
    }

    private static String sha(String content) {
        return SyncRunner.sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ConnectorProperties properties() {
        return new ConnectorProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(10), "10.0.0.0/8", 50, 500,
                DataSize.ofGigabytes(2), 100_000, 32, Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofMinutes(1),
                Duration.ofMinutes(2), 20, 20, 3, 100,
                new ConnectorProperties.Smb(Duration.ofSeconds(10), Duration.ofSeconds(60), true, false), null, null);
    }

    /** A file server in memory: null content means "cannot be read". */
    private static final class FakeSource implements FileSource {
        private final List<ListedFile> files = new ArrayList<>();
        private final java.util.Map<String, String> contents = new java.util.HashMap<>();
        private final List<String> reads = new ArrayList<>();
        boolean complete = true;
        boolean incremental;
        Instant highWater;

        void add(String path, String content) {
            add(path, content, false);
        }

        void add(String path, String content, boolean restricted) {
            long size = content == null ? 4 : content.getBytes(StandardCharsets.UTF_8).length;
            files.add(new ListedFile(path, files.size() + 1, size, MODIFIED, restricted));
            contents.put(path, content);
        }

        void addAt(String path, String content, Instant modified) {
            files.add(new ListedFile(path, files.size() + 1, 4, modified, false));
            contents.put(path, content);
        }

        @Override
        public Listing list(Runnable keepAlive) {
            keepAlive.run();
            return new Listing(List.copyOf(files), complete, complete ? null : "DIRECTORY_UNREADABLE", 0, incremental,
                    highWater);
        }

        @Override
        public byte[] read(String relativePath, long maxBytes) {
            reads.add(relativePath);
            String content = contents.get(relativePath);
            if (content == null) throw SourceException.file("READ_FAILED");
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
        }
    }
}
