package com.verso.connector.sharepoint;

import com.verso.connector.domain.DeltaCheckpoint;
import com.verso.connector.domain.DriveTree;
import com.verso.connector.domain.SharePointSite;
import com.verso.connector.domain.SourcePaths;
import com.verso.connector.domain.SyncPlanner.ListedFile;
import com.verso.connector.smb.FileSource;
import com.verso.connector.smb.SourceException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The files of one or more document libraries of a SharePoint Online site or a OneDrive as a {@link FileSource}
 * (ADR-0045), so the planner, item state, run history, deletion safety, audit and metrics of ADR-0038 apply unchanged.
 *
 * <ul>
 *   <li><b>Identity:</b> {@code drives/<drive id>/items/<item id>}, never a name: a renamed or moved file stays the
 *       same item. The fingerprint is (a 64-bit hash of the cTag, else the eTag; size; last modified).</li>
 *   <li><b>Listing:</b> {@code /drives/{d}/root/delta}. Without a stored delta link (first run, the periodic full
 *       listing, a 410 Gone) every item is enumerated and the listing may delete by absence; with one, only what
 *       changed is listed, and deletions come from the {@code deleted} facet and from files that moved out of scope.
 *       Paging follows {@code @odata.nextLink} on Graph's own origin only; the round ends at {@code @odata.deltaLink}.</li>
 *   <li><b>Folders:</b> delta reports a folder rename, move or deletion without the children, and without
 *       {@code parentReference.path}; paths are built from the stored folder tree. An incremental round that changes a
 *       folder the tree knew, or names a parent it does not know, is dropped and the library is listed from scratch.</li>
 *   <li><b>Permissions (v1 safety rule):</b> with {@code skipUnique}, a file whose permissions differ from the library's
 *       ({@link PermissionRule}) is not synced: checked for every fetched file, and for every listed file in the
 *       periodic sweep. An unanswered check fails closed (left out of the listing, which is then incomplete; a fetch is
 *       retried later).</li>
 * </ul>
 * Names live only in memory for the document name; logs carry ids and counts.
 */
public class SharePointFileSource implements FileSource {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9!_-]{1,200}");
    private static final Pattern SITE_ID = Pattern.compile("[A-Za-z0-9.-]{1,253},[0-9A-Fa-f-]{36},[0-9A-Fa-f-]{36}");
    private static final Pattern ITEM_PATH = Pattern.compile("drives/([A-Za-z0-9!_-]{1,200})/items/([A-Za-z0-9!_-]{1,200})");
    private static final String SELECT = "id,name,size,file,folder,root,deleted,parentReference,lastModifiedDateTime,cTag,eTag";
    private static final int MAX_EXTRA_PAGES = 10;

    private final SharePointRunSettings settings;
    private final GraphClient client;
    private final int pageSize;
    private final int maxListed;
    private final int maxDepth;
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Boolean> restrictions = new HashMap<>();
    private final Map<String, String> roots = new HashMap<>();

    public SharePointFileSource(SharePointRunSettings settings, GraphClient client, int pageSize, int maxListed,
                                int maxDepth) {
        this.settings = settings;
        this.client = client;
        this.pageSize = pageSize;
        this.maxListed = maxListed;
        this.maxDepth = maxDepth;
    }

    /** A library resolved for this run. */
    record Drive(String id, String rootId) {
    }

    /** A permission check that did not give an answer. */
    private static final class Unknown extends RuntimeException {
        Unknown() {
            super("PERMISSIONS_UNKNOWN", null, false, false);
        }
    }

    /** The incremental round cannot be applied as it is (a known folder changed, an unknown parent): list from scratch. */
    private static final class Escalate extends RuntimeException {
        Escalate() {
            super("ESCALATE", null, false, false);
        }
    }

    @Override
    public Listing list(Runnable keepAlive) {
        client.keepAlive(keepAlive);
        List<Drive> drives = drives(site());
        Map<String, DeltaCheckpoint.Stored> stored = new HashMap<>();
        for (DeltaCheckpoint.Stored s : settings.stored()) stored.put(s.driveId(), s);
        boolean incremental = !settings.full() && !drives.isEmpty()
                && drives.stream().allMatch(d -> stored.containsKey(d.id()) && d.rootId().equals(stored.get(d.id()).rootId()));
        boolean sweep = settings.sweep();
        if (incremental) {
            try {
                return listing(drives, stored, true, sweep, keepAlive);
            } catch (GraphClient.ResyncRequired e) {
                // 410 Gone: the delta link expired. Everything again, with the permission sweep.
                sweep = true;
            } catch (Escalate e) {
                // A folder the tree knew changed: its children's paths and scope must be recomputed.
            }
            names.clear();
            restrictions.clear();
        }
        try {
            return listing(drives, Map.of(), false, sweep, keepAlive);
        } catch (GraphClient.ResyncRequired | Escalate e) {
            // Neither can happen from scratch; a server that answers so cannot be listed.
            throw SourceException.fatal("INVALID_RESPONSE");
        }
    }

    private Listing listing(List<Drive> drives, Map<String, DeltaCheckpoint.Stored> stored, boolean incremental,
                            boolean sweep, Runnable keepAlive) {
        Round round = new Round(incremental, sweep);
        List<DeltaCheckpoint.Drive> checkpoints = new ArrayList<>();
        for (Drive drive : drives) {
            DeltaCheckpoint.Drive checkpoint = round.drive(drive, stored.get(drive.id()), keepAlive);
            if (checkpoint == null) break;
            checkpoints.add(checkpoint);
        }
        DeltaCheckpoint state = round.complete ? new DeltaCheckpoint(!incremental, List.copyOf(checkpoints)) : null;
        return new Listing(List.copyOf(round.files), round.complete, round.problem, round.unsafe, incremental, null,
                Set.copyOf(round.deleted), state);
    }

    /** One pass over the libraries. */
    private final class Round {
        final boolean incremental;
        final boolean sweep;
        final List<ListedFile> files = new ArrayList<>();
        final Set<String> deleted = new HashSet<>();
        boolean complete = true;
        String problem;
        int unsafe;
        int seen;

        Round(boolean incremental, boolean sweep) {
            this.incremental = incremental;
            this.sweep = sweep;
        }

        void incomplete(String code) {
            complete = false;
            if (problem == null) problem = code;
        }

        /** The library's changes; null when the round was cut (then the listing is incomplete). */
        DeltaCheckpoint.Drive drive(Drive drive, DeltaCheckpoint.Stored stored, Runnable keepAlive) {
            DriveTree tree = new DriveTree(drive.rootId(), incremental ? stored.folders() : Map.of());
            URI next = incremental ? linkOf(stored.deltaLink())
                    : client.graph("/drives/" + drive.id() + "/root/delta?$select=" + encode(SELECT) + "&$top=" + pageSize);
            if (next == null) throw new Escalate();
            Map<String, JsonNode> changes = new LinkedHashMap<>();
            String deltaLink = null;
            while (next != null) {
                JsonNode page = client.json(next);
                if (page == null) throw SourceException.fatal("DRIVE_NOT_FOUND");
                JsonNode value = page.path("value");
                if (!value.isArray()) throw SourceException.fatal("INVALID_RESPONSE");
                for (JsonNode item : value) {
                    keepAlive.run();
                    if (++seen > maxListed) {
                        incomplete("LISTING_TOO_LARGE");
                        return null;
                    }
                    String id = item.path("id").asString("");
                    if (!ID.matcher(id).matches()) {
                        unsafe++;
                        continue;
                    }
                    // The same item may come more than once in one round: the last state wins.
                    changes.remove(id);
                    changes.put(id, item);
                }
                String nextLink = page.path("@odata.nextLink").asString(null);
                String finalLink = page.path("@odata.deltaLink").asString(null);
                if (nextLink != null) {
                    next = linkOf(nextLink);
                    if (next == null) {
                        // A link to another origin or path is not followed: the round is cut, nothing may be deleted.
                        incomplete("LISTING_LINK_REFUSED");
                        return null;
                    }
                } else if (finalLink != null) {
                    if (linkOf(finalLink) == null) {
                        incomplete("LISTING_LINK_REFUSED");
                        return null;
                    }
                    deltaLink = finalLink.strip();
                    next = null;
                } else {
                    incomplete("INVALID_RESPONSE");
                    return null;
                }
            }
            Map<String, DriveTree.Folder> upserts = new HashMap<>();
            Set<String> removed = new HashSet<>();
            folders(drive, tree, changes, upserts, removed);
            files(drive, tree, changes);
            return new DeltaCheckpoint.Drive(drive.id(), drive.rootId(), deltaLink, !incremental, Map.copyOf(upserts),
                    Set.copyOf(removed));
        }

        private void folders(Drive drive, DriveTree tree, Map<String, JsonNode> changes,
                             Map<String, DriveTree.Folder> upserts, Set<String> removed) {
            for (JsonNode item : changes.values()) {
                String id = item.path("id").asString("");
                if (item.hasNonNull("root") || id.equals(drive.rootId())) continue;
                if (item.hasNonNull("deleted")) {
                    if (tree.remove(id)) {
                        // A known folder went: its children may not be reported one by one.
                        if (incremental) throw new Escalate();
                        upserts.remove(id);
                        removed.add(id);
                    } else if (incremental) {
                        deleted.add(path(drive.id(), id));
                    }
                    continue;
                }
                if (!item.hasNonNull("folder")) continue;
                String parent = item.path("parentReference").path("id").asString("");
                String name = item.path("name").asString("");
                if (!ID.matcher(parent).matches() || name.isEmpty() || name.length() > 400) {
                    unsafe++;
                    continue;
                }
                if (tree.put(id, parent, name) && incremental) throw new Escalate();
                upserts.put(id, new DriveTree.Folder(parent, name));
                removed.remove(id);
            }
        }

        private void files(Drive drive, DriveTree tree, Map<String, JsonNode> changes) {
            for (JsonNode item : changes.values()) {
                if (item.hasNonNull("root") || item.hasNonNull("deleted") || item.hasNonNull("folder")) continue;
                if (!item.hasNonNull("file")) continue;
                String id = item.path("id").asString("");
                String itemPath = path(drive.id(), id);
                String parent = item.path("parentReference").path("id").asString("");
                String name = item.path("name").asString("");
                String relative = ID.matcher(parent).matches() ? tree.path(parent, name, maxDepth) : null;
                if (relative == null) {
                    // A parent the tree does not know: from scratch in an incremental round; a gap in a full one.
                    if (incremental) throw new Escalate();
                    incomplete("PARENT_UNKNOWN");
                    continue;
                }
                if (DriveTree.UNSAFE.equals(relative)) {
                    unsafe++;
                    if (incremental) deleted.add(itemPath);
                    continue;
                }
                String inScope = scoped(relative);
                if (inScope == null) {
                    // Moved out of the folder or the filters (or never in them): gone from this connector's view.
                    if (incremental) deleted.add(itemPath);
                    continue;
                }
                Instant when = time(item.path("lastModifiedDateTime").asString(""));
                long size = item.path("size").asLong(-1);
                String tag = item.path("cTag").asString(item.path("eTag").asString(""));
                if (when == null || size < 0 || tag.isEmpty()) {
                    unsafe++;
                    continue;
                }
                boolean restricted = false;
                if (sweep && settings.skipUnique()) {
                    try {
                        restricted = restricted(drive, id, itemPath);
                    } catch (Unknown e) {
                        // Fail closed: an unchecked file is neither ingested nor counted as gone.
                        incomplete("RESTRICTION_CHECK_FAILED");
                        continue;
                    }
                }
                files.add(new ListedFile(itemPath, fingerprint(tag), size, when, restricted));
                names.put(itemPath, name);
                deleted.remove(itemPath);
            }
        }
    }

    /** The path relative to the configured folder, when the file is in scope (supported type, globs); else null. */
    private String scoped(String drivePath) {
        String prefix = settings.folderPrefix();
        String relative;
        if (prefix.isEmpty()) {
            relative = drivePath;
        } else if (drivePath.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT) + "/")) {
            // SharePoint names are case-insensitive, like the folder an admin typed.
            relative = drivePath.substring(prefix.length() + 1);
        } else {
            return null;
        }
        if (!SourcePaths.isCandidate(SourcePaths.fileName(relative))) return null;
        if (!settings.include().isEmpty() && !settings.include().matches(relative)) return null;
        return settings.exclude().matches(relative) ? null : relative;
    }

    /** The site's Graph id. */
    private String site() {
        SharePointSite site = SharePointSite.parse(settings.siteUrl(), settings.siteId(), false);
        JsonNode answer = client.json(client.graph(site.graphPath() + "?$select=id"));
        if (answer == null) throw SourceException.fatal("SITE_NOT_FOUND");
        String id = answer.path("id").asString("");
        if (!SITE_ID.matcher(id).matches()) throw SourceException.fatal("INVALID_RESPONSE");
        return id;
    }

    /** The configured libraries (by name or id; none: the default library) with their root folder ids. */
    private List<Drive> drives(String siteId) {
        List<String> ids = new ArrayList<>();
        if (settings.drives().isEmpty()) {
            JsonNode drive = client.json(client.graph("/sites/" + siteId + "/drive?$select=id"));
            if (drive == null) throw SourceException.fatal("DRIVE_NOT_FOUND");
            ids.add(drive.path("id").asString(""));
        } else {
            Map<String, String> byName = new HashMap<>();
            Set<String> known = new HashSet<>();
            URI next = client.graph("/sites/" + siteId + "/drives?$select=id,name");
            for (int page = 0; next != null && page <= MAX_EXTRA_PAGES; page++) {
                JsonNode answer = client.json(next);
                if (answer == null) throw SourceException.fatal("DRIVE_NOT_FOUND");
                for (JsonNode drive : answer.path("value")) {
                    String id = drive.path("id").asString("");
                    known.add(id);
                    byName.putIfAbsent(drive.path("name").asString("").toLowerCase(Locale.ROOT), id);
                }
                next = linkOf(answer.path("@odata.nextLink").asString(null));
            }
            for (String wanted : settings.drives()) {
                String id = known.contains(wanted) ? wanted : byName.get(wanted.toLowerCase(Locale.ROOT));
                if (id == null) throw SourceException.fatal("DRIVE_NOT_FOUND");
                if (!ids.contains(id)) ids.add(id);
            }
        }
        List<Drive> drives = new ArrayList<>();
        for (String id : ids) {
            if (!ID.matcher(id).matches()) throw SourceException.fatal("INVALID_RESPONSE");
            JsonNode root = client.json(client.graph("/drives/" + id + "/root?$select=id"));
            if (root == null) throw SourceException.fatal("DRIVE_NOT_FOUND");
            String rootId = root.path("id").asString("");
            if (!ID.matcher(rootId).matches()) throw SourceException.fatal("INVALID_RESPONSE");
            roots.put(id, rootId);
            drives.add(new Drive(id, rootId));
        }
        return drives;
    }

    /** Whether the file's permissions differ from the library's; cached for the run. */
    private boolean restricted(Drive drive, String itemId, String itemPath) {
        Boolean cached = restrictions.get(itemPath);
        if (cached != null) return cached;
        URI next = client.graph("/drives/" + drive.id() + "/items/" + itemId + "/permissions");
        boolean unique = false;
        for (int page = 0; next != null; page++) {
            if (page > MAX_EXTRA_PAGES) throw new Unknown();
            JsonNode answer = client.json(next);
            if (answer == null) throw new Unknown();
            Boolean result = PermissionRule.unique(answer.path("value"), drive.rootId());
            if (result == null && page == 0) throw new Unknown();
            if (Boolean.TRUE.equals(result)) {
                unique = true;
                break;
            }
            String link = answer.path("@odata.nextLink").asString(null);
            next = link == null ? null : linkOf(link);
            if (link != null && next == null) throw new Unknown();
        }
        restrictions.put(itemPath, unique);
        return unique;
    }

    @Override
    public byte[] read(String relativePath, long maxBytes) {
        Matcher m = ITEM_PATH.matcher(relativePath);
        if (!m.matches()) throw SourceException.file("NOT_FOUND");
        String driveId = m.group(1);
        String itemId = m.group(2);
        String rootId = roots.get(driveId);
        if (rootId == null) throw SourceException.file("NOT_FOUND");
        if (settings.skipUnique()) {
            boolean restricted;
            try {
                restricted = restricted(new Drive(driveId, rootId), itemId, relativePath);
            } catch (Unknown e) {
                // Fail closed and retry later: the last good version stays, nothing new is ingested unchecked.
                throw SourceException.file("RESTRICTION_CHECK_FAILED");
            }
            if (restricted) throw SourceException.file("RESTRICTED");
        }
        return client.download(client.graph("/drives/" + driveId + "/items/" + itemId + "/content"), maxBytes);
    }

    @Override
    public String documentName(ListedFile file) {
        String name = names.get(file.path());
        return name != null ? name : "sharepoint-" + Long.toHexString(file.fileId());
    }

    @Override
    public void close() {
        names.clear();
        restrictions.clear();
        roots.clear();
    }

    private URI linkOf(String link) {
        return link == null ? null : client.egress().graphLink(link);
    }

    static String path(String driveId, String itemId) {
        return "drives/" + driveId + "/items/" + itemId;
    }

    /** A 64-bit fingerprint of the content tag (it changes with the content, not with a rename). */
    static long fingerprint(String tag) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tag.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) value = (value << 8) | (digest[i] & 0xff);
            return value;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** ISO-8601 with an offset, to milliseconds; null when unreadable. */
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
        return "SharePointFileSource[" + settings + "]";
    }
}
