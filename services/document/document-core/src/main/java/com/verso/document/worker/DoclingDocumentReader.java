package com.verso.document.worker;

import com.verso.document.worker.DoclingClient.DoclingException;
import com.verso.document.worker.DoclingClient.Failure;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads the blocks of a DoclingDocument (docling-core 2.x JSON; ADR-0032) in reading order: the body tree is walked
 * from {@code #/body}, every {@code $ref} is resolved inside the document only ({@code #/texts/n}, {@code #/tables/n},
 * {@code #/groups/n}, {@code #/pictures/n}), each item at most once, with bounded depth and item count. The sidecar is
 * trusted to be ours, not its output: a malformed document is an INVALID_RESPONSE, never a parser crash.
 *
 * <ul>
 *   <li>Page headers and footers (furniture) are left out: repeated on every page, they only add noise.</li>
 *   <li>A list item becomes "- text"; headings stay plain lines, as the JDK parser yields them.</li>
 *   <li>A table becomes a Markdown table with one header row (llm-rules 6.3: a table stays on its first page); its
 *       caption precedes it. Several header rows are merged per column; without a marked header the first row is it.</li>
 * </ul>
 */
final class DoclingDocumentReader {

    /** One block of text; page is 0 for formats without pages (DOCX). */
    record Block(int page, String text, boolean heading, boolean table) {
    }

    static final int MAX_DEPTH = 64;
    static final int MAX_ITEMS = 500_000;
    static final int MAX_TABLE_CELLS = 100_000;
    private static final Pattern REF = Pattern.compile("#/(texts|tables|groups|pictures)/(\\d{1,7})");
    private static final Set<String> SKIPPED_LABELS = Set.of("page_header", "page_footer");
    private static final Set<String> HEADING_LABELS = Set.of("title", "section_header");

    private final JsonNode document;
    private final Set<String> seen = new HashSet<>();
    private final List<Block> blocks = new ArrayList<>();
    private int items;

    private DoclingDocumentReader(JsonNode document) {
        this.document = document;
    }

    /** The document's blocks in reading order. */
    static List<Block> read(JsonNode document) {
        if (!"DoclingDocument".equals(document.path("schema_name").asString(""))) invalid();
        JsonNode body = document.path("body");
        if (!body.isObject()) invalid();
        DoclingDocumentReader reader = new DoclingDocumentReader(document);
        reader.walk(body, 0);
        return reader.blocks;
    }

    /** Number of pages the sidecar saw; 0 when the document has no page list. */
    static int pageCount(JsonNode document) {
        JsonNode pages = document.path("pages");
        return pages.isObject() ? pages.size() : 0;
    }

    private void walk(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) invalid();
        JsonNode children = node.path("children");
        if (children.isMissingNode() || children.isNull()) return;
        if (!children.isArray()) invalid();
        for (JsonNode child : children) {
            if (++items > MAX_ITEMS) invalid();
            String ref = child.path("$ref").asString("");
            Matcher m = REF.matcher(ref);
            if (!m.matches()) invalid();
            if (!seen.add(ref)) continue;
            JsonNode item = document.path(m.group(1)).path(Integer.parseInt(m.group(2)));
            if (!item.isObject()) invalid();
            String layer = item.path("content_layer").asString("body");
            if (!"body".equals(layer)) continue;
            switch (m.group(1)) {
                case "texts" -> text(item, depth);
                case "tables" -> table(item);
                default -> walk(item, depth + 1);
            }
        }
    }

    private void text(JsonNode item, int depth) {
        String label = item.path("label").asString("text");
        if (!SKIPPED_LABELS.contains(label)) {
            String text = clean(item.path("text").asString(""));
            if (!text.isEmpty()) {
                boolean heading = HEADING_LABELS.contains(label);
                if ("list_item".equals(label)) text = "- " + text;
                blocks.add(new Block(page(item), text, heading, false));
            }
        }
        walk(item, depth + 1);
    }

    private void table(JsonNode item) {
        int page = page(item);
        // The caption is the table's context ("Tablo 3: Hız sınırları"); it goes right before the table, once.
        for (JsonNode caption : item.path("captions")) {
            String ref = caption.path("$ref").asString("");
            Matcher m = REF.matcher(ref);
            if (!m.matches() || !"texts".equals(m.group(1)) || !seen.add(ref)) continue;
            String text = clean(document.path("texts").path(Integer.parseInt(m.group(2))).path("text").asString(""));
            if (!text.isEmpty()) blocks.add(new Block(page, text, false, false));
        }
        // Captions and footnotes are the table's children; nothing else of the table is read as text.
        for (JsonNode child : item.path("children")) seen.add(child.path("$ref").asString(""));
        String markdown = markdown(item.path("data").path("grid"));
        if (!markdown.isEmpty()) blocks.add(new Block(page, markdown, false, true));
    }

    /** GitHub-flavoured Markdown: one header row, a separator, the body rows; empty rows and columns dropped. */
    static String markdown(JsonNode grid) {
        if (!grid.isArray() || grid.isEmpty()) return "";
        List<List<String>> rows = new ArrayList<>();
        List<Boolean> header = new ArrayList<>();
        int columns = 0;
        long cells = 0;
        for (JsonNode row : grid) {
            if (!row.isArray()) invalid();
            List<String> texts = new ArrayList<>(row.size());
            boolean allHeader = true;
            boolean any = false;
            for (JsonNode cell : row) {
                if (++cells > MAX_TABLE_CELLS) invalid();
                String text = clean(cell.path("text").asString("")).replace("|", "\\|");
                texts.add(text);
                if (!text.isEmpty()) {
                    any = true;
                    allHeader &= cell.path("column_header").asBoolean(false);
                }
            }
            if (!any) continue;
            columns = Math.max(columns, texts.size());
            rows.add(texts);
            header.add(allHeader);
        }
        if (rows.isEmpty()) return "";
        boolean[] used = new boolean[columns];
        for (List<String> row : rows) for (int c = 0; c < row.size(); c++) used[c] |= !row.get(c).isEmpty();
        int headerRows = 0;
        while (headerRows < rows.size() - 1 && header.get(headerRows)) headerRows++;
        if (headerRows == 0) headerRows = 1;
        if (rows.size() == 1) return line(rows.getFirst(), used);
        List<String> head = new ArrayList<>();
        for (int c = 0; c < columns; c++) {
            List<String> parts = new ArrayList<>();
            for (int r = 0; r < headerRows; r++) {
                String text = c < rows.get(r).size() ? rows.get(r).get(c) : "";
                // Spanning header cells repeat in every grid position: keep each text once per column.
                if (!text.isEmpty() && !parts.contains(text)) parts.add(text);
            }
            head.add(String.join(" / ", parts));
        }
        StringBuilder out = new StringBuilder(line(head, used)).append('\n').append('|');
        for (int c = 0; c < columns; c++) if (used[c]) out.append(" --- |");
        for (int r = headerRows; r < rows.size(); r++) out.append('\n').append(line(rows.get(r), used));
        return out.toString();
    }

    private static String line(List<String> cells, boolean[] used) {
        StringBuilder out = new StringBuilder("|");
        for (int c = 0; c < used.length; c++) {
            if (!used[c]) continue;
            out.append(' ').append(c < cells.size() ? cells.get(c) : "").append(" |");
        }
        return out.toString();
    }

    /** The first page the item is on; 0 without provenance (DOCX). */
    private static int page(JsonNode item) {
        JsonNode prov = item.path("prov");
        if (!prov.isArray() || prov.isEmpty()) return 0;
        int page = prov.get(0).path("page_no").asInt(-1);
        if (page < 1) invalid();
        return page;
    }

    /** One line: inside a cell or a paragraph a line break is layout, not content. */
    private static String clean(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static void invalid() {
        throw new DoclingException(Failure.INVALID_RESPONSE);
    }
}
