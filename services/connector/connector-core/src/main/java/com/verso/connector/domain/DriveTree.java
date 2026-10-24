package com.verso.connector.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The folders of one document library (ADR-0045), so a file's path can be built from its parent id: Graph's delta
 * does not return {@code parentReference.path}, and a folder that was renamed or moved is reported alone, not with its
 * children. The tree is kept between runs; an incremental round that renames, moves or deletes a folder it knows makes
 * the run list the whole library again (the children's paths, scope and deletions follow from that).
 */
public final class DriveTree {

    /** A folder's parent and name; the name is used for paths only (folder filter, globs), never logged. */
    public record Folder(String parentId, String name) {
        public Folder {
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(name, "name");
        }
    }

    /** A path that crosses a name {@link SourcePaths#isSafeName} refuses: the file is out of reach. */
    public static final String UNSAFE = "\u0000";

    private final String rootId;
    private final Map<String, Folder> folders;

    public DriveTree(String rootId, Map<String, Folder> folders) {
        this.rootId = Objects.requireNonNull(rootId, "rootId");
        this.folders = new HashMap<>(folders);
    }

    public String rootId() {
        return rootId;
    }

    public boolean knows(String id) {
        return folders.containsKey(id);
    }

    public int size() {
        return folders.size();
    }

    /**
     * Adds or replaces a folder.
     *
     * @return true when it renames or moves a folder the tree already knew (its children's paths changed silently)
     */
    public boolean put(String id, String parentId, String name) {
        if (id.equals(rootId)) return false;
        Folder previous = folders.put(id, new Folder(parentId, name));
        return previous != null && !previous.equals(new Folder(parentId, name));
    }

    /** @return true when the folder was known (its children may have gone with it) */
    public boolean remove(String id) {
        return folders.remove(id) != null;
    }

    /**
     * The path below the library root of an item with this parent and name ("A/B/name"), {@link #UNSAFE} when a name on
     * the way is not a safe path segment, or null when the chain does not reach the root (an unknown parent, a loop, or
     * deeper than {@code maxDepth}).
     */
    public String path(String parentId, String name, int maxDepth) {
        if (!SourcePaths.isSafeName(name)) return UNSAFE;
        Deque<String> segments = new ArrayDeque<>();
        segments.push(name);
        String current = parentId;
        int depth = 0;
        while (!rootId.equals(current)) {
            Folder folder = current == null ? null : folders.get(current);
            if (folder == null || ++depth > maxDepth) return null;
            if (!SourcePaths.isSafeName(folder.name())) return UNSAFE;
            segments.push(folder.name());
            current = folder.parentId();
        }
        String path = String.join("/", segments);
        return path.length() > SourcePaths.MAX_PATH ? UNSAFE : path;
    }

    /** A copy of the folders (the checkpoint of a run). */
    public Map<String, Folder> folders() {
        return Map.copyOf(folders);
    }

    @Override
    public String toString() {
        return "DriveTree[folders=" + folders.size() + "]";
    }
}
