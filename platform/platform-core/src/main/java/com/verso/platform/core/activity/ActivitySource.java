package com.verso.platform.core.activity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A module's part of the activity centre (ADR-0058). Each module lists what only it knows (its runs, its hook actions,
 * its pending approvals, its knowledge changes) from its own schema, without another module reading its tables; the
 * controller that serves GET /v1/admin/activity only merges them. A module that is not deployed (the skills module of
 * another branch, for example) simply has no bean: its entries are absent, which is the feature detection.
 *
 * <p>Rules for an implementation: never an individual account id or user name in an entry (an actor is a team, a role
 * or a system component); document and card names only when {@link ActivityQuery#viewer()} may read them (ADR-0028);
 * never document, question or answer text beyond such a name; at most {@link ActivityQuery#limit()} entries, newest
 * first; never throw for an unavailable resource (log one line and return what is known).
 */
public interface ActivitySource {

    /** A fixed lowercase word ({@code [a-z][a-z0-9-]{1,30}}): the prefix of this source's entry ids. */
    String key();

    /**
     * The entries that started inside the query's window, newest first. An APPROVAL entry is returned while it is
     * pending, whatever its age (it waits for a person until someone acts), as long as it started before the window's
     * end.
     */
    List<ActivityEntry> entries(ActivityQuery query);

    /** The entry with its steps, sources and model-call metadata; empty when unknown or not readable. */
    default Optional<ActivityDetail> detail(UUID id, ActivityViewer viewer) {
        return Optional.empty();
    }
}
