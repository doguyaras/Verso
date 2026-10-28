package com.verso.qa.service;

import com.verso.platform.core.activity.ActivityViewer;
import com.verso.qa.api.dto.ActivityDetailResponse;
import com.verso.qa.api.dto.ActivityPageResponse;
import com.verso.qa.api.dto.ActivitySummaryResponse;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The activity centre (ADR-0058): merges what every module's {@link com.verso.platform.core.activity.ActivitySource}
 * reports. Reads no module's tables itself; the viewer only decides which names a source may show.
 */
public interface ActivityService {

    /** The most days one request may span. */
    int MAX_DAYS = 90;
    /** page x size + size never exceeds this: the deepest entry a client can page to. */
    int MAX_DEPTH = 500;
    int MAX_SIZE = 50;

    ActivityPageResponse page(ActivityViewer viewer, Filter filter);

    ActivitySummaryResponse summary(ActivityViewer viewer);

    ActivityDetailResponse detail(ActivityViewer viewer, String id);

    /**
     * The filter of GET /v1/admin/activity; every value may be null (no filter) except page and size.
     *
     * @param from inclusive day in the installation's time zone; default {@code to} minus 6 days
     * @param to   inclusive day; default today
     */
    record Filter(String category, String type, String status, LocalDate from, LocalDate to, UUID collection,
                  int page, int size) {
    }
}
