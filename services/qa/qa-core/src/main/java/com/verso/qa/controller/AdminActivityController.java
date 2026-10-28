package com.verso.qa.controller;

import com.verso.platform.core.activity.ActivityViewer;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.ActivityDetailResponse;
import com.verso.qa.api.dto.ActivityPageResponse;
import com.verso.qa.api.dto.ActivitySummaryResponse;
import com.verso.qa.service.ActivityService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/activity (ADR-0058): the activity centre's timeline, its status strip and one entry's drawer, for
 * {@code verso-admin} only (ADR-0019, declared once on the class; everyone else gets 403 ACCESS_DENIED). Read-only and
 * never cached. The admin and the admin's groups come from the validated token and only decide which document and
 * card names the modules may show (ADR-0028); no response carries an account id or a user name.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/activity")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class AdminActivityController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private final ActivityService activity;
    private final CallerGroups groups;

    public AdminActivityController(ActivityService activity, CallerGroups groups) {
        this.activity = activity;
        this.groups = groups;
    }

    /** One page of the timeline, newest first; {@code from}/{@code to} are inclusive days (default: the last 7). */
    @GetMapping
    public ResponseEntity<ActivityPageResponse> page(
            @CurrentAccount AccountId admin,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "collection", required = false) UUID collection,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        ActivityService.Filter filter = new ActivityService.Filter(category, type, status, from, to, collection, page,
                size);
        return ResponseEntity.ok().cacheControl(PRIVATE).body(activity.page(viewerOf(admin), filter));
    }

    /** The status strip: what needs attention in the last 7 days and what happened in the last 24 hours. */
    @GetMapping("/summary")
    public ResponseEntity<ActivitySummaryResponse> summary(@CurrentAccount AccountId admin) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(activity.summary(viewerOf(admin)));
    }

    /** One entry with its steps, sources and model-call metadata; {@code id} is "{source}.{uuid}". */
    @GetMapping("/{id}")
    public ResponseEntity<ActivityDetailResponse> detail(@CurrentAccount AccountId admin,
                                                         @PathVariable("id") String id) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(activity.detail(viewerOf(admin), id));
    }

    private ActivityViewer viewerOf(AccountId admin) {
        return new ActivityViewer(admin.value(), groups.current());
    }
}
