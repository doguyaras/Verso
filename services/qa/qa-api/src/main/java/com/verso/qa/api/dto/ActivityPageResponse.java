package com.verso.qa.api.dto;

import java.util.List;

/**
 * GET /v1/admin/activity (ADR-0058): one page of the merged timeline, newest first.
 *
 * @param sources the source keys deployed in this installation ("connector", "qa", "document", "audit", and e.g.
 *                "work" when the skill library of ADR-0057 is deployed): the panel shows a tab's empty state only for a known source
 * @param partial true when a source could not answer; the page then holds what the others returned
 * @param hasMore whether a next page exists within the caps (page x size at most 500)
 */
public record ActivityPageResponse(List<ActivityItemResponse> items, int page, int size, boolean hasMore,
                                   List<String> sources, boolean partial) {

    public ActivityPageResponse {
        items = List.copyOf(items);
        sources = List.copyOf(sources);
    }
}
