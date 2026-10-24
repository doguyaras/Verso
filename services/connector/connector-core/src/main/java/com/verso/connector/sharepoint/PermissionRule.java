package com.verso.connector.sharepoint;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The v1 safety rule's signal (ADR-0045): does a file's access differ from its library's? Read from the file's
 * {@code GET /drives/{d}/items/{i}/permissions}: a permission that is inherited carries {@code inheritedFrom}, the
 * ancestor it comes from. The file is treated as having unique permissions when any permission
 * <ul>
 *   <li>has no {@code inheritedFrom} (granted on the file itself: broken inheritance, a direct grant or a sharing
 *       link, which may widen access as well as narrow it), or</li>
 *   <li>is inherited from an ancestor other than the library root (a folder below it with unique permissions).</li>
 * </ul>
 * Fail closed: an empty or unreadable answer is "unknown" (null), never "inherits". Chosen over the {@code shared}
 * facet and {@code Prefer: hierarchicalsharing}, which need Sites.FullControl.All to be reliable and report sharing
 * links rather than broken inheritance; the price is one request per checked file.
 */
final class PermissionRule {

    private static final Pattern ROOT_PATH = Pattern.compile("/drives/[^/]+/root:?/?");

    private PermissionRule() {}

    /**
     * @param permissions the {@code value} array of one page
     * @param rootId      the library root's item id
     * @return true unique, false inherited from the library root only, null unknown
     */
    static Boolean unique(JsonNode permissions, String rootId) {
        if (permissions == null || !permissions.isArray() || permissions.isEmpty()) return null;
        for (JsonNode permission : permissions) {
            if (!permission.isObject()) return null;
            JsonNode from = permission.path("inheritedFrom");
            if (!from.isObject()) return true;
            String id = from.path("id").asString("");
            if (!id.isEmpty()) {
                if (!id.equals(rootId)) return true;
                continue;
            }
            String path = from.path("path").asString("");
            if (!ROOT_PATH.matcher(path).matches()) return true;
        }
        return false;
    }
}
