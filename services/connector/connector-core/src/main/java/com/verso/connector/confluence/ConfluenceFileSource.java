package com.verso.connector.confluence;

import com.verso.connector.domain.CqlBuilder;
import com.verso.connector.domain.SyncPlanner.ListedFile;
import com.verso.connector.smb.FileSource;
import com.verso.connector.smb.SourceException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The pages (and optionally the PDF/DOCX attachments) of some Confluence spaces as a {@link FileSource} (ADR-0044), so
 * the SMB sync's planner, item state, run history, deletion safety, audit and metrics apply unchanged.
 *
 * <ul>
 *   <li><b>Paths</b> are ids, never titles: {@code pages/<id>} and {@code attachments/<page id>/<attachment id>}; a
 *       renamed page stays the same item. The fingerprint is (id, version number, version time); for an attachment
 *       (id, size, version time).</li>
 *   <li><b>Listing:</b> CQL over the spaces with the optional label and ancestor filters. Incremental runs ask only for
 *       pages modified since the cursor (minus an overlap) and never delete; a full listing walks every page.</li>
 *   <li><b>Restrictions</b> (v1 safety rule): with {@code skipRestricted}, each listed page's read restrictions and
 *       those of its ancestors (inherited) are read through the restrictions API; any user or group restriction marks the
 *       page restricted, and the sync skips it. A check that fails leaves the page out of the listing and marks the
 *       listing incomplete: nothing unchecked is ingested and nothing is deleted on its account (fail closed).</li>
 *   <li><b>Content:</b> the storage format, converted to Markdown by {@link StorageFormatConverter}.</li>
 * </ul>
 * Titles live only in memory for the document name; logs carry ids and counts.
 */
public class ConfluenceFileSource implements FileSource {

    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,18}");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("(att)?([0-9]{1,18})");
    private static final int MAX_TITLE = 150;

    private final ConfluenceRunSettings settings;
    private final ConfluenceClient client;
    private final Duration cursorOverlap;
    private final int maxListed;
    private final Clock clock;
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, URI> downloads = new HashMap<>();
    private final Map<Long, Boolean> restrictionCache = new HashMap<>();

    public ConfluenceFileSource(ConfluenceRunSettings settings, ConfluenceClient client, Duration cursorOverlap,
                                int maxListed, Clock clock) {
        this.settings = settings;
        this.client = client;
        this.cursorOverlap = cursorOverlap;
        this.maxListed = maxListed;
        this.clock = clock;
    }

    /** A restriction check that did not give an answer. */
    private static final class Unknown extends RuntimeException {
        Unknown() {
            super("RESTRICTION_UNKNOWN", null, false, false);
        }
    }

    @Override
    public Listing list(Runnable keepAlive) {
        client.keepAlive(keepAlive);
        boolean incremental = !settings.full() && settings.cursor() != null;
        Long since = incremental ? CqlBuilder.windowMinutes(settings.cursor(), clock.instant(), cursorOverlap) : null;
        String cql = CqlBuilder.pages(settings.spaceKeys(), settings.label(), settings.ancestorId(), since);
        String expand = settings.skipRestricted() ? "version,ancestors" : "version";
        URI next = client.site().api("/rest/api/content/search?cql=" + encode(cql) + "&limit=" + settings.pageSize()
                + "&expand=" + encode(expand));
        List<ListedFile> files = new ArrayList<>();
        boolean complete = true;
        String problem = null;
        int unsafe = 0;
        Instant highWater = null;
        walk:
        while (next != null) {
            JsonNode page = client.json(next);
            if (page == null) throw SourceException.fatal("SITE_NOT_FOUND");
            JsonNode results = page.path("results");
            if (!results.isArray()) throw SourceException.fatal("INVALID_RESPONSE");
            for (JsonNode result : results) {
                keepAlive.run();
                String id = result.path("id").asString("");
                Instant when = time(result.path("version").path("when").asString(""));
                long version = result.path("version").path("number").asLong(-1);
                if (!NUMERIC_ID.matcher(id).matches() || when == null || version < 0) {
                    unsafe++;
                    continue;
                }
                if (files.size() >= maxListed) {
                    complete = false;
                    problem = "LISTING_TOO_LARGE";
                    break walk;
                }
                long pageId = Long.parseLong(id);
                boolean restricted;
                try {
                    restricted = settings.skipRestricted() && restricted(pageId, result.path("ancestors"));
                } catch (Unknown e) {
                    // Fail closed: an unchecked page is neither ingested nor counted as gone.
                    complete = false;
                    if (problem == null) problem = "RESTRICTION_CHECK_FAILED";
                    continue;
                }
                String path = "pages/" + pageId;
                files.add(new ListedFile(path, pageId, version, when, restricted));
                names.put(path, title(result.path("title").asString(""), pageId) + ".md");
                if (highWater == null || when.isAfter(highWater)) highWater = when;
                if (settings.includeAttachments()) {
                    String attachmentProblem = attachments(pageId, restricted, files, keepAlive);
                    if (attachmentProblem != null) {
                        complete = false;
                        if (problem == null) problem = attachmentProblem;
                    }
                }
            }
            next = following(page);
            if (next == null && page.path("_links").hasNonNull("next")) {
                // A next link we do not follow (another path or host): the walk is cut, nothing may be deleted.
                complete = false;
                if (problem == null) problem = "LISTING_LINK_REFUSED";
            }
            if (results.isEmpty()) break;
        }
        return new Listing(List.copyOf(files), complete, problem, unsafe, incremental, highWater);
    }

    /** PDF and DOCX attachments of a page; a problem code when their listing was cut. */
    private String attachments(long pageId, boolean restricted, List<ListedFile> files, Runnable keepAlive) {
        URI next = client.site().api("/rest/api/content/" + pageId + "/child/attachment?limit=" + settings.pageSize()
                + "&expand=version");
        while (next != null) {
            JsonNode page = client.json(next);
            if (page == null) return "ATTACHMENTS_UNREADABLE";
            for (JsonNode result : page.path("results")) {
                keepAlive.run();
                var id = ATTACHMENT_ID.matcher(result.path("id").asString(""));
                String fileName = result.path("title").asString("");
                Instant when = time(result.path("version").path("when").asString(""));
                long size = result.path("extensions").path("fileSize").asLong(-1);
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".pdf") && !lower.endsWith(".docx")) continue;
                if (!id.matches() || when == null || size < 0) continue;
                URI download = client.site().resolve(result.path("_links").path("download").asString(""));
                if (download == null) continue;
                if (files.size() >= maxListed) return "LISTING_TOO_LARGE";
                long attachmentId = Long.parseLong(id.group(2));
                String path = "attachments/" + pageId + "/" + attachmentId;
                files.add(new ListedFile(path, attachmentId, size, when, restricted));
                names.put(path, title(fileName, attachmentId));
                downloads.put(path, download);
            }
            next = following(page);
            if (next == null && page.path("_links").hasNonNull("next")) return "LISTING_LINK_REFUSED";
            if (page.path("results").isEmpty()) break;
        }
        return null;
    }

    /** The page or any ancestor has a read restriction (view restrictions are inherited). */
    private boolean restricted(long pageId, JsonNode ancestors) {
        if (restrictedItself(pageId)) return true;
        if (ancestors.isArray()) {
            for (JsonNode ancestor : ancestors) {
                String id = ancestor.path("id").asString("");
                if (!NUMERIC_ID.matcher(id).matches()) throw new Unknown();
                if (restrictedItself(Long.parseLong(id))) return true;
            }
        }
        return false;
    }

    private boolean restrictedItself(long contentId) {
        Boolean cached = restrictionCache.get(contentId);
        if (cached != null) return cached;
        JsonNode answer = client.json(client.site().api("/rest/api/content/" + contentId
                + "/restriction/byOperation/read?expand=" + encode("restrictions.user,restrictions.group")));
        if (answer == null) throw new Unknown();
        JsonNode restrictions = answer.path("restrictions");
        JsonNode users = restrictions.path("user");
        JsonNode groups = restrictions.path("group");
        if (!users.isObject() || !groups.isObject()) throw new Unknown();
        boolean restricted = count(users) > 0 || count(groups) > 0;
        restrictionCache.put(contentId, restricted);
        return restricted;
    }

    /** Entries of a restriction list; a size the server reports wins over the page of results it sent. */
    private static long count(JsonNode list) {
        long size = list.path("size").asLong(0);
        long total = list.path("totalSize").asLong(0);
        return Math.max(Math.max(size, total), list.path("results").size());
    }

    @Override
    public byte[] read(String relativePath, long maxBytes) {
        if (relativePath.startsWith("attachments/")) {
            URI download = downloads.get(relativePath);
            if (download == null) throw SourceException.file("NOT_FOUND");
            return client.bytes(download, maxBytes);
        }
        if (!relativePath.startsWith("pages/") || !NUMERIC_ID.matcher(relativePath.substring(6)).matches()) {
            throw SourceException.file("NOT_FOUND");
        }
        JsonNode page = client.json(client.site().api("/rest/api/content/" + relativePath.substring(6)
                + "?expand=" + encode("body.storage,version")));
        if (page == null) throw SourceException.file("NOT_FOUND");
        String storage = page.path("body").path("storage").path("value").asString(null);
        if (storage == null) throw SourceException.file("UNSUPPORTED");
        String markdown;
        try {
            markdown = StorageFormatConverter.toMarkdown(page.path("title").asString(""), storage);
        } catch (RuntimeException e) {
            // One page the parser cannot take is skipped (until it changes); it must not fail the whole run.
            throw new SourceException("UNSUPPORTED", false, e);
        }
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw SourceException.file("TOO_LARGE");
        return bytes;
    }

    @Override
    public String documentName(ListedFile file) {
        String name = names.get(file.path());
        return name != null ? name : "confluence-" + file.fileId() + ".md";
    }

    @Override
    public void close() {
        names.clear();
        downloads.clear();
    }

    private URI following(JsonNode page) {
        String next = page.path("_links").path("next").asString(null);
        return next == null ? null : client.site().resolve(next);
    }

    /** A title as a file name: no path or control characters, bounded; the id when nothing is left. */
    static String title(String raw, long id) {
        String cleaned = StorageFormatConverter.clean(raw).replaceAll("[\\\\/:*?\"<>|\\n\\t]", " ")
                .replaceAll("\\s+", " ").strip();
        if (cleaned.length() > MAX_TITLE) cleaned = cleaned.substring(0, MAX_TITLE).strip();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        return cleaned.isEmpty() ? "confluence-" + id : cleaned;
    }

    /** ISO-8601 with an offset (Cloud "…Z", Data Center "…+03:00"), to milliseconds; null when unreadable. */
    static Instant time(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public String toString() {
        return "ConfluenceFileSource[" + settings + "]";
    }
}
