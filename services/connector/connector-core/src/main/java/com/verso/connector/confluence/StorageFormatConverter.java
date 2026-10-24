package com.verso.connector.confluence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/**
 * Confluence storage format (XHTML with {@code ac:} and {@code ri:} elements) to Markdown (ADR-0044). The document
 * pipeline chunks Markdown by headings and keeps a table on its own (llm-rules 6.3), so headings stay {@code #} lines and
 * tables become GitHub tables. Macros are handled by a closed list:
 * <ul>
 *   <li>code and noformat: a fenced block of their plain-text body;</li>
 *   <li>panels (info, note, warning, tip, panel, expand, details, section, column, excerpt): their rich-text body;</li>
 *   <li>every other macro: its rich-text body if it has one, else nothing. Macro parameters, scripts, styles, raw HTML
 *       macros, images, emoticons and placeholders never become text.</li>
 * </ul>
 * Pages are untrusted content: invisible characters (bidirectional overrides, zero-width marks, controls) are removed so
 * text a reader cannot see does not reach the model either (llm-rules 4). The parser is jsoup's XML mode (CDATA of code
 * blocks survives); deep nesting is flattened beyond {@link #MAX_DEPTH} instead of recursing further.
 */
public final class StorageFormatConverter {

    static final int MAX_DEPTH = 100;

    private static final Set<String> BLOCKS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table",
            "pre", "blockquote", "hr", "section", "article", "ac:layout", "ac:layout-section", "ac:layout-cell",
            "ac:rich-text-body", "ac:task-list", "dl");
    private static final Set<String> PANELS = Set.of("info", "note", "warning", "tip", "panel", "expand", "details",
            "section", "column", "excerpt", "ui-expand", "ui-tab", "ui-tabs");
    private static final Set<String> CODE = Set.of("code", "noformat", "code-block");
    private static final Set<String> DROPPED = Set.of("script", "style", "ac:parameter", "ac:plain-text-body",
            "ac:image", "ac:emoticon", "ac:placeholder", "ri:attachment", "ri:url", "ri:page", "ri:user", "ri:space",
            "iframe", "object", "embed", "svg", "math", "head", "title");

    private StorageFormatConverter() {}

    /** The page as Markdown: its title as the first heading, then the body. */
    public static String toMarkdown(String title, String storage) {
        Document document = Jsoup.parse("<verso-root>" + (storage == null ? "" : storage) + "</verso-root>", "",
                Parser.xmlParser());
        Out out = new Out();
        String heading = clean(title == null ? "" : title).replaceAll("\\s+", " ").strip();
        if (!heading.isEmpty()) out.block("# " + escapeLineStart(heading));
        Element root = document.selectFirst("verso-root");
        if (root != null) blocks(root, out, 0);
        return out.text();
    }

    /** Blocks of a container: runs of inline nodes become paragraphs, block elements render on their own. */
    private static void blocks(Element parent, Out out, int depth) {
        StringBuilder paragraph = new StringBuilder();
        for (Node child : parent.childNodes()) {
            if (child instanceof Element element && isBlock(element)) {
                out.paragraph(paragraph);
                block(element, out, depth + 1);
            } else {
                inline(child, paragraph, depth + 1);
            }
        }
        out.paragraph(paragraph);
    }

    private static boolean isBlock(Element element) {
        String tag = tag(element);
        if (BLOCKS.contains(tag)) return true;
        if (tag.equals("ac:structured-macro") || tag.equals("ac:macro")) {
            String name = macroName(element);
            return CODE.contains(name) || PANELS.contains(name) || element.getElementsByTag("ac:rich-text-body").size() > 0;
        }
        return false;
    }

    private static void block(Element element, Out out, int depth) {
        if (depth > MAX_DEPTH) {
            out.block(clean(element.text()).strip());
            return;
        }
        String tag = tag(element);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = inlineText(element, depth).replace('\n', ' ').strip();
                if (!text.isEmpty()) out.block("#".repeat(tag.charAt(1) - '0') + " " + text);
            }
            case "ul", "ol" -> {
                List<String> lines = new ArrayList<>();
                list(element, lines, "", depth);
                out.block(String.join("\n", lines));
            }
            case "table" -> out.block(table(element, depth));
            case "pre" -> out.block(fence(element.wholeText()));
            case "hr" -> out.block("---");
            case "blockquote" -> {
                Out quoted = new Out();
                blocks(element, quoted, depth);
                String text = quoted.text();
                if (!text.isEmpty()) out.block("> " + text.replace("\n", "\n> "));
            }
            case "ac:task-list" -> {
                List<String> lines = new ArrayList<>();
                for (Element task : element.children()) {
                    if (!tag(task).equals("ac:task")) continue;
                    Element status = child(task, "ac:task-status");
                    Element body = child(task, "ac:task-body");
                    boolean done = status != null && status.text().strip().equalsIgnoreCase("complete");
                    String text = body == null ? "" : inlineText(body, depth).replace('\n', ' ').strip();
                    if (!text.isEmpty()) lines.add((done ? "- [x] " : "- [ ] ") + text);
                }
                out.block(String.join("\n", lines));
            }
            case "ac:structured-macro", "ac:macro" -> macro(element, out, depth);
            default -> blocks(element, out, depth);
        }
    }

    private static void macro(Element macro, Out out, int depth) {
        String name = macroName(macro);
        if (CODE.contains(name)) {
            Element body = child(macro, "ac:plain-text-body");
            if (body != null) out.block(fence(body.wholeText()));
            return;
        }
        if (name.equals("html") || name.equals("html-include") || name.equals("iframe") || name.equals("widget")) return;
        Element body = child(macro, "ac:rich-text-body");
        if (body != null) blocks(body, out, depth);
    }

    private static void inline(Node node, StringBuilder text, int depth) {
        if (node instanceof CDataNode) return;
        if (node instanceof TextNode t) {
            text.append(clean(t.getWholeText()).replaceAll("\\s+", " "));
            return;
        }
        if (!(node instanceof Element element)) return;
        if (depth > MAX_DEPTH) {
            text.append(clean(element.text()));
            return;
        }
        String tag = tag(element);
        if (DROPPED.contains(tag)) return;
        switch (tag) {
            case "br" -> text.append('\n');
            case "strong", "b" -> wrap(element, text, "**", depth);
            case "em", "i" -> wrap(element, text, "*", depth);
            case "code", "tt" -> {
                String code = clean(element.text()).replace("`", "'").strip();
                if (!code.isEmpty()) text.append('`').append(code).append('`');
            }
            case "time" -> text.append(clean(element.attr("datetime")));
            case "ac:link" -> text.append(linkText(element, depth));
            case "ac:structured-macro", "ac:macro" -> {
                if (PANELS.contains(macroName(element)) || child(element, "ac:rich-text-body") != null) {
                    Element body = child(element, "ac:rich-text-body");
                    if (body != null) for (Node n : body.childNodes()) inline(n, text, depth + 1);
                }
            }
            default -> {
                for (Node n : element.childNodes()) inline(n, text, depth + 1);
            }
        }
    }

    private static void wrap(Element element, StringBuilder text, String mark, int depth) {
        StringBuilder inner = new StringBuilder();
        for (Node n : element.childNodes()) inline(n, inner, depth + 1);
        String value = inner.toString().strip();
        if (value.isEmpty()) return;
        if (!text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))
                && Character.isWhitespace(inner.charAt(0))) {
            text.append(' ');
        }
        text.append(mark).append(value).append(mark);
        if (Character.isWhitespace(inner.charAt(inner.length() - 1))) text.append(' ');
    }

    /** A link's visible text: its body, else the target page's title or the attachment's file name. */
    private static String linkText(Element link, int depth) {
        Element body = child(link, "ac:link-body");
        if (body != null) return inlineText(body, depth);
        Element plain = child(link, "ac:plain-text-link-body");
        if (plain != null) return clean(plain.wholeText()).strip();
        Element page = child(link, "ri:page");
        if (page != null) return clean(page.attr("ri:content-title")).strip();
        Element attachment = child(link, "ri:attachment");
        if (attachment != null) return clean(attachment.attr("ri:filename")).strip();
        return "";
    }

    private static void list(Element list, List<String> lines, String indent, int depth) {
        boolean ordered = tag(list).equals("ol");
        int number = 1;
        for (Element item : list.children()) {
            if (!tag(item).equals("li")) continue;
            StringBuilder text = new StringBuilder();
            List<Element> nested = new ArrayList<>();
            for (Node n : item.childNodes()) {
                if (n instanceof Element e && (tag(e).equals("ul") || tag(e).equals("ol"))) nested.add(e);
                else inline(n, text, depth + 1);
            }
            String marker = ordered ? (number++) + ". " : "- ";
            lines.add(indent + marker + text.toString().replace("\n", " ").strip());
            for (Element sub : nested) {
                if (depth < MAX_DEPTH) list(sub, lines, indent + " ".repeat(marker.length()), depth + 1);
            }
        }
    }

    /** A GitHub table: the first row is the header; nested tables are flattened into their cell's text. */
    private static String table(Element table, int depth) {
        List<List<String>> rows = new ArrayList<>();
        for (Element row : rowsOf(table)) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.children()) {
                String t = tag(cell);
                if (!t.equals("td") && !t.equals("th")) continue;
                String text = inlineText(cell, depth).replace('\n', ' ').replace("|", "\\|").strip();
                cells.add(text);
            }
            if (cells.stream().anyMatch(c -> !c.isEmpty())) rows.add(cells);
        }
        if (rows.isEmpty()) return "";
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            out.append('|');
            for (int c = 0; c < columns; c++) out.append(' ').append(c < cells.size() ? cells.get(c) : "").append(" |");
            out.append('\n');
            if (r == 0) {
                out.append('|');
                out.append(" --- |".repeat(columns));
                out.append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    private static List<Element> rowsOf(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            String t = tag(child);
            if (t.equals("tr")) rows.add(child);
            else if (t.equals("thead") || t.equals("tbody") || t.equals("tfoot")) {
                for (Element row : child.children()) if (tag(row).equals("tr")) rows.add(row);
            }
        }
        return rows;
    }

    /** The inline text of an element whose children may include blocks (a cell with paragraphs, a list item). */
    private static String inlineText(Element element, int depth) {
        StringBuilder text = new StringBuilder();
        for (Node n : element.childNodes()) {
            if (n instanceof Element e && isBlock(e) && !text.isEmpty()) text.append(' ');
            inline(n, text, depth + 1);
        }
        return text.toString().replaceAll("[ \\t]+", " ").strip();
    }

    private static String fence(String code) {
        String body = clean(code).replace("\r\n", "\n").strip();
        if (body.isEmpty()) return "";
        // A fence longer than any backtick run inside, so the code cannot close it.
        int longest = 0;
        int run = 0;
        for (char c : body.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + "\n" + body + "\n" + fence;
    }

    private static Element child(Element parent, String tag) {
        for (Element child : parent.children()) if (tag(child).equals(tag)) return child;
        return null;
    }

    private static String macroName(Element macro) {
        return macro.attr("ac:name").toLowerCase(Locale.ROOT);
    }

    private static String tag(Element element) {
        return element.tagName().toLowerCase(Locale.ROOT);
    }

    /** Drops controls (but line breaks and tabs), bidirectional overrides and zero-width marks; NBSP becomes a space. */
    static String clean(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a0') {
                out.append(' ');
            } else if (c == '\n' || c == '\t') {
                out.append(c);
            } else if (c < 0x20 || (c >= 0x7f && c < 0xa0) || (c >= 0x200b && c <= 0x200f)
                    || (c >= 0x202a && c <= 0x202e) || (c >= 0x2060 && c <= 0x2069) || c == 0xfeff) {
                continue;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A paragraph starting with "#" is text, not a heading. */
    private static String escapeLineStart(String text) {
        return text.startsWith("#") ? "\\" + text : text;
    }

    /** Blocks joined by one blank line; trailing spaces and runs of blank lines removed. */
    private static final class Out {
        private final StringBuilder text = new StringBuilder();

        void block(String block) {
            if (block == null || block.isBlank()) return;
            if (!text.isEmpty()) text.append("\n\n");
            text.append(block.strip());
        }

        void paragraph(StringBuilder paragraph) {
            String value = paragraph.toString().replaceAll("[ \\t]+\n", "\n").replaceAll("\n[ \\t]+", "\n").strip();
            paragraph.setLength(0);
            if (!value.isEmpty()) block(escapeLineStart(value));
        }

        String text() {
            return text.toString().replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
        }
    }
}
