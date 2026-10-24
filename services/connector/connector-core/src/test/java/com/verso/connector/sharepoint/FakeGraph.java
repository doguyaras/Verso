package com.verso.connector.sharepoint;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Graph and Entra in memory for the SharePoint unit tests (ADR-0045): one site, one library, folders and files,
 * a change log behind driveItem/delta (tokens are change sequence numbers), 410 Gone for an expired token, scripted 429s,
 * the content endpoint's redirect to a SharePoint download URL, and the token endpoint. Records every request and the
 * Authorization header it carried.
 */
final class FakeGraph implements GraphTransport {

    static final String GRAPH = "https://graph.microsoft.com/v1.0";
    static final String SITE_ID = "contoso.sharepoint.com,0f0e0d0c-0b0a-0908-0706-050403020100,00010203-0405-0607-0809-0a0b0c0d0e0f";
    static final String DRIVE = "b!drive1";
    static final String ROOT = "ROOTID";
    static final String ACCESS_TOKEN = "eyJ0eXAiOiJKV1QifQ.access-token-value.sig";

    /** A file or folder; permissions is the JSON array the permissions endpoint returns. */
    static final class Item {
        final String id;
        String name;
        String parent;
        final boolean folder;
        String content;
        int version = 1;
        boolean deleted;
        String permissions;

        Item(String id, String name, String parent, boolean folder, String content) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            this.folder = folder;
            this.content = content;
            this.permissions = "[{\"id\":\"p1\",\"roles\":[\"read\"],\"inheritedFrom\":{\"driveId\":\"" + DRIVE
                    + "\",\"id\":\"" + ROOT + "\"}}]";
        }
    }

    final Map<String, Item> items = new LinkedHashMap<>();
    final List<String> log = new ArrayList<>();
    final List<String> requests = new ArrayList<>();
    final List<String> authorizations = new ArrayList<>();
    final List<String> forms = new ArrayList<>();
    int pageSize = 2;
    boolean gone;
    int throttle;
    int unauthorized;
    String retryAfter = "1";
    String downloadHost = "https://contoso.sharepoint.com";
    String nextLinkOverride;
    int tokenStatus = 200;

    void folder(String id, String name, String parent) {
        items.put(id, new Item(id, name, parent, true, null));
        log.add(id);
    }

    void file(String id, String name, String parent, String content) {
        items.put(id, new Item(id, name, parent, false, content));
        log.add(id);
    }

    void change(String id, String content) {
        Item item = items.get(id);
        item.content = content;
        item.version++;
        log.add(id);
    }

    void rename(String id, String name) {
        items.get(id).name = name;
        log.add(id);
    }

    void delete(String id) {
        items.get(id).deleted = true;
        log.add(id);
    }

    void unique(String id) {
        items.get(id).permissions = "[{\"id\":\"p2\",\"roles\":[\"write\"],\"grantedToV2\":{\"user\":{\"id\":\"u\"}}},"
                + "{\"id\":\"p1\",\"roles\":[\"read\"],\"inheritedFrom\":{\"id\":\"" + ROOT + "\"}}]";
    }

    @Override
    public Response get(URI uri, String authorization, Map<String, String> headers, long maxBytes) throws IOException {
        String path = uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        requests.add(uri.getScheme() + "://" + uri.getRawAuthority() + path + (query.isEmpty() ? "" : "?" + query));
        authorizations.add(authorization);
        if (throttle > 0) {
            throttle--;
            return new Response(429, null, retryAfter, new byte[0]);
        }
        if (unauthorized > 0 && authorization != null) {
            unauthorized--;
            return new Response(401, null, null, new byte[0]);
        }
        if (uri.toString().startsWith(downloadHost + "/download/")) {
            Item item = items.get(path.substring("/download/".length()));
            return item == null ? new Response(404, null, null, new byte[0]) : body(item.content, maxBytes);
        }
        String p = path.substring("/v1.0".length());
        if (p.equals("/sites/contoso.sharepoint.com:/sites/hr")) return json("{\"id\":\"" + SITE_ID + "\"}");
        if (p.equals("/sites/" + SITE_ID + "/drive")) return json("{\"id\":\"" + DRIVE + "\"}");
        if (p.equals("/sites/" + SITE_ID + "/drives")) {
            return json("{\"value\":[{\"id\":\"" + DRIVE + "\",\"name\":\"Belgeler\"},{\"id\":\"b!other\",\"name\":\"Arşiv\"}]}");
        }
        if (p.equals("/drives/" + DRIVE + "/root")) return json("{\"id\":\"" + ROOT + "\"}");
        if (p.equals("/drives/" + DRIVE + "/root/delta")) return delta(query);
        if (p.startsWith("/drives/" + DRIVE + "/items/") && p.endsWith("/permissions")) {
            Item item = items.get(p.substring(("/drives/" + DRIVE + "/items/").length(), p.length() - "/permissions".length()));
            return item == null ? new Response(404, null, null, new byte[0]) : json("{\"value\":" + item.permissions + "}");
        }
        if (p.startsWith("/drives/" + DRIVE + "/items/") && p.endsWith("/content")) {
            String id = p.substring(("/drives/" + DRIVE + "/items/").length(), p.length() - "/content".length());
            if (!items.containsKey(id)) return new Response(404, null, null, new byte[0]);
            return new Response(302, downloadHost + "/download/" + id + "?tempauth=x", null, new byte[0]);
        }
        return new Response(404, null, null, new byte[0]);
    }

    @Override
    public Response postForm(URI uri, String form, long maxBytes) {
        requests.add("POST " + uri);
        forms.add(form);
        if (tokenStatus != 200) return new Response(tokenStatus, null, null, new byte[0]);
        return json("{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"" + ACCESS_TOKEN + "\"}");
    }

    /** No token: everything alive (root first); token=N: the last state of what changed after change N. */
    private Response delta(String query) {
        Map<String, String> q = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) q.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        List<String> ids = new ArrayList<>();
        if (q.containsKey("token")) {
            if (gone) return new Response(410, null, null, new byte[0]);
            int since = Integer.parseInt(q.get("token"));
            for (int i = since; i < log.size(); i++) {
                ids.remove(log.get(i));
                ids.add(log.get(i));
            }
        } else {
            ids.add(ROOT);
            for (Item item : items.values()) if (!item.deleted) ids.add(item.id);
        }
        int skip = Integer.parseInt(q.getOrDefault("skip", "0"));
        List<String> slice = ids.subList(Math.min(skip, ids.size()), Math.min(skip + pageSize, ids.size()));
        StringBuilder out = new StringBuilder("{\"value\":[");
        for (int i = 0; i < slice.size(); i++) {
            if (i > 0) out.append(',');
            out.append(itemJson(slice.get(i)));
        }
        out.append("],");
        if (skip + pageSize < ids.size()) {
            String next = nextLinkOverride != null ? nextLinkOverride : GRAPH + "/drives/" + DRIVE + "/root/delta?"
                    + (q.containsKey("token") ? "token=" + q.get("token") + "&" : "") + "skip=" + (skip + pageSize);
            out.append("\"@odata.nextLink\":\"").append(next).append("\"}");
        } else {
            out.append("\"@odata.deltaLink\":\"").append(GRAPH).append("/drives/").append(DRIVE)
                    .append("/root/delta?token=").append(log.size()).append("\"}");
        }
        return json(out.toString());
    }

    private String itemJson(String id) {
        if (id.equals(ROOT)) return "{\"id\":\"" + ROOT + "\",\"name\":\"root\",\"root\":{},\"folder\":{}}";
        Item item = items.get(id);
        if (item.deleted) return "{\"id\":\"" + id + "\",\"deleted\":{\"state\":\"deleted\"},\"parentReference\":{\"id\":\"" + item.parent + "\"}}";
        String common = "\"id\":\"" + id + "\",\"name\":\"" + item.name + "\",\"parentReference\":{\"driveId\":\"" + DRIVE
                + "\",\"id\":\"" + item.parent + "\"},\"lastModifiedDateTime\":\"2026-10-2" + Math.min(item.version, 9)
                + "T10:00:00Z\",\"eTag\":\"e" + item.version + "\",\"cTag\":\"c" + id + "." + item.version + "\"";
        if (item.folder) return "{" + common + ",\"folder\":{\"childCount\":1}}";
        return "{" + common + ",\"size\":" + item.content.length() + ",\"file\":{\"mimeType\":\"text/plain\"}}";
    }

    private static Response json(String body) {
        return new Response(200, null, null, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Response body(String content, long maxBytes) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new com.verso.connector.confluence.ConfluenceTransport.TooLarge();
        return new Response(200, null, null, bytes);
    }
}
