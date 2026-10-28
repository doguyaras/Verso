package com.verso.work.controller;

import com.verso.document.api.dto.Reader;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillRatingRequest;
import com.verso.work.api.dto.SkillRunEditRequest;
import com.verso.work.api.dto.SkillRunPage;
import com.verso.work.api.dto.SkillRunRequest;
import com.verso.work.api.dto.SkillRunResponse;
import com.verso.work.api.dto.SkillSummary;
import com.verso.work.config.WorkProperties;
import com.verso.work.exception.WorkErrorCode;
import com.verso.work.exception.WorkServiceException;
import com.verso.work.service.SkillCatalog;
import com.verso.work.service.SkillRunService;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The skill library for every authenticated user (ADR-0057): the gallery, a skill's form definition, starting a run,
 * and the caller's own runs. The caller is the validated token (sub and groups) only; a run id of another user is a
 * 404. Every response is no-store: runs carry the caller's inputs and document excerpts.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TaskSkillController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final SkillCatalog catalog;
    private final SkillRunService runs;
    private final CallerGroups groups;
    private final WorkProperties properties;

    public TaskSkillController(SkillCatalog catalog, SkillRunService runs, CallerGroups groups, WorkProperties properties) {
        this.catalog = catalog;
        this.runs = runs;
        this.groups = groups;
        this.properties = properties;
    }

    @GetMapping("/v1/skills")
    public ResponseEntity<List<SkillSummary>> list() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(runs.gallery());
    }

    @GetMapping("/v1/skills/{skillId}")
    public ResponseEntity<SkillDefinition> get(@PathVariable("skillId") String skillId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(catalog.require(skillId).definition());
    }

    /** 202: the run is stored as RUNNING and continues in the background; follow it with the events endpoint. */
    @PostMapping(path = "/v1/skills/{skillId}/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SkillRunResponse> start(@CurrentAccount AccountId account,
                                                  @PathVariable("skillId") String skillId,
                                                  @Valid @RequestBody SkillRunRequest request) {
        SkillRunResponse run = runs.start(reader(account), skillId, request.inputs());
        return ResponseEntity.accepted().location(URI.create("/v1/skill-runs/" + run.id())).cacheControl(NO_STORE)
                .body(run);
    }

    @GetMapping("/v1/skill-runs")
    public ResponseEntity<SkillRunPage> history(@CurrentAccount AccountId account,
                                                @RequestParam(name = "page", defaultValue = "0") int page,
                                                @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(runs.page(account.value(), page, size));
    }

    @GetMapping("/v1/skill-runs/{runId}")
    public ResponseEntity<SkillRunResponse> run(@CurrentAccount AccountId account, @PathVariable("runId") UUID runId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(runs.get(reader(account), runId));
    }

    /**
     * Progress as server-sent events: {@code stage} whenever the stage or step changes, {@code done} once. Read from the
     * run's row, so it works from any instance; ends when the run ends, the client leaves or {@code max-stream} passes.
     */
    @GetMapping("/v1/skill-runs/{runId}/events")
    public ResponseEntity<SseEmitter> events(@CurrentAccount AccountId account, @PathVariable("runId") UUID runId) {
        String owner = account.value();
        // A run that is not the caller's is a plain 404 envelope before any stream opens.
        runs.event(owner, runId).orElseThrow(() -> new WorkServiceException(WorkErrorCode.RUN_NOT_FOUND, "NOT_FOUND"));
        RunEventStream stream = new RunEventStream(() -> runs.event(owner, runId), properties.streamPoll(),
                properties.maxStream());
        if (!stream.start()) throw new WorkServiceException(WorkErrorCode.STREAMS_BUSY, "STREAMS_BUSY");
        return ResponseEntity.ok().cacheControl(NO_STORE).header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(stream.emitter());
    }

    @PutMapping(path = "/v1/skill-runs/{runId}/output", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SkillRunResponse> edit(@CurrentAccount AccountId account, @PathVariable("runId") UUID runId,
                                                 @Valid @RequestBody SkillRunEditRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(runs.edit(reader(account), runId, request.output()));
    }

    @PostMapping("/v1/skill-runs/{runId}/approve")
    public ResponseEntity<SkillRunResponse> approve(@CurrentAccount AccountId account, @PathVariable("runId") UUID runId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(runs.approve(reader(account), runId));
    }

    @PostMapping(path = "/v1/skill-runs/{runId}/rating", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> rate(@CurrentAccount AccountId account, @PathVariable("runId") UUID runId,
                                     @Valid @RequestBody SkillRatingRequest request) {
        runs.rate(reader(account), runId, request.rating());
        return ResponseEntity.noContent().cacheControl(NO_STORE).build();
    }

    /** The draft as a file: {@code format=MARKDOWN} or {@code JSON}; always an attachment, never rendered inline. */
    @GetMapping("/v1/skill-runs/{runId}/export")
    public ResponseEntity<byte[]> export(@CurrentAccount AccountId account, @PathVariable("runId") UUID runId,
                                         @RequestParam(name = "format", defaultValue = "MARKDOWN") String format) {
        SkillRunService.Export export = runs.export(reader(account), runId, format);
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.fileName())
                        .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(export.mediaType()))
                .body(export.body().getBytes(StandardCharsets.UTF_8));
    }

    @DeleteMapping("/v1/skill-runs/{runId}")
    public ResponseEntity<Void> delete(@CurrentAccount AccountId account, @PathVariable("runId") UUID runId) {
        runs.delete(reader(account), runId);
        return ResponseEntity.noContent().cacheControl(NO_STORE).build();
    }

    private Reader reader(AccountId account) {
        return new Reader(account.value(), groups.current());
    }
}
