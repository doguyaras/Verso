package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.worker.DoclingClient.DoclingException;
import com.verso.document.worker.DoclingClient.Failure;
import com.verso.document.worker.DoclingDocumentReader.Block;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0032: the DoclingDocument JSON of docling-serve 1.38 (docling-core 2.104, schema 1.10) becomes blocks in reading
 * order; tables become Markdown with a header row; furniture is left out; a malformed document is INVALID_RESPONSE.
 */
class DoclingDocumentReaderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The shape docling-serve returned for the sample PDFs, cut down to what the reader uses. */
    static String document(String body, String texts, String tables, String groups) {
        return """
                {"schema_name": "DoclingDocument", "version": "1.10.0",
                 "body": {"self_ref": "#/body", "children": [%s], "content_layer": "body"},
                 "furniture": {"self_ref": "#/furniture", "children": [], "content_layer": "furniture"},
                 "texts": [%s], "tables": [%s], "groups": [%s], "pictures": [],
                 "pages": {"1": {"page_no": 1}, "2": {"page_no": 2}}}""".formatted(body, texts, tables, groups);
    }

    static String text(int i, String label, String layer, int page, String text) {
        return """
                {"self_ref": "#/texts/%d", "label": "%s", "content_layer": "%s", "children": [],
                 "prov": [{"page_no": %d, "bbox": {"l": 0, "t": 0, "r": 1, "b": 1}}], "text": "%s"}"""
                .formatted(i, label, layer, page, text);
    }

    static String cell(String text, boolean header) {
        return "{\"text\": \"%s\", \"column_header\": %s, \"row_header\": false}".formatted(text, header);
    }

    @Test
    void read_whenADocumentHasHeadingsListsATableAndFooters_givesBlocksInReadingOrder() {
        String texts = String.join(",",
                text(0, "section_header", "body", 1, "3.2 Hız sınırları"),
                text(1, "text", "body", 1, "Forklift hızı alana göre değişir."),
                text(2, "page_footer", "furniture", 1, "isg.pdf - sayfa 1 / 2"),
                text(3, "caption", "body", 2, "Tablo 1: Azami hız"),
                text(4, "list_item", "body", 2, "Yaya geçitlerinde dur."));
        String table = """
                {"self_ref": "#/tables/0", "label": "table", "content_layer": "body",
                 "children": [{"$ref": "#/texts/3"}], "captions": [{"$ref": "#/texts/3"}],
                 "prov": [{"page_no": 2}],
                 "data": {"grid": [[%s, %s], [%s, %s], [%s, %s]]}}""".formatted(
                cell("Alan", true), cell("Azami hız", true),
                cell("Raf koridorları", false), cell("6 km/sa", false),
                cell("Dış saha | açık", false), cell("15 km/sa", false));
        String group = "{\"self_ref\": \"#/groups/0\", \"label\": \"list\", \"children\": [{\"$ref\": \"#/texts/4\"}]}";
        JsonNode doc = JSON.readTree(document(
                "{\"$ref\": \"#/texts/0\"}, {\"$ref\": \"#/texts/1\"}, {\"$ref\": \"#/texts/2\"}, "
                        + "{\"$ref\": \"#/tables/0\"}, {\"$ref\": \"#/groups/0\"}",
                texts, table, group));

        List<Block> blocks = DoclingDocumentReader.read(doc);

        assertThat(blocks).containsExactly(
                new Block(1, "3.2 Hız sınırları", true, false),
                new Block(1, "Forklift hızı alana göre değişir.", false, false),
                new Block(2, "Tablo 1: Azami hız", false, false),
                new Block(2, "| Alan | Azami hız |\n| --- | --- |\n| Raf koridorları | 6 km/sa |\n"
                        + "| Dış saha \\| açık | 15 km/sa |", false, true),
                new Block(2, "- Yaya geçitlerinde dur.", false, false));
        assertThat(DoclingDocumentReader.pageCount(doc)).isEqualTo(2);
    }

    /** Two header rows merge per column; a spanning header repeated over its columns is kept once per column. */
    @Test
    void markdown_whenTwoHeaderRowsAndEmptyColumns_mergesTheHeaderAndDropsEmptyColumns() {
        JsonNode grid = JSON.readTree("[[%s, %s, %s], [%s, %s, %s], [%s, %s, %s], [%s, %s, %s]]".formatted(
                cell("Raf", true), cell("Kapasite", true), cell("", false),
                cell("Raf", true), cell("kg", true), cell("", false),
                cell("Palet", false), cell("1.000", false), cell("", false),
                cell("", false), cell("", false), cell("", false)));
        assertThat(DoclingDocumentReader.markdown(grid))
                .isEqualTo("| Raf | Kapasite / kg |\n| --- | --- |\n| Palet | 1.000 |");
    }

    @Test
    void markdown_whenNoRowIsMarkedAsHeader_usesTheFirstRow() {
        JsonNode grid = JSON.readTree("[[%s, %s], [%s, %s]]".formatted(
                cell("Renk", false), cell("Anlamı", false), cell("Yeşil", false), cell("Kabul", false)));
        assertThat(DoclingDocumentReader.markdown(grid)).isEqualTo("| Renk | Anlamı |\n| --- | --- |\n| Yeşil | Kabul |");
    }

    /** The sidecar's output is checked like any input: references stay inside the document, depth is bounded. */
    @Test
    void read_whenTheDocumentIsMalformed_failsWithInvalidResponse() {
        assertInvalid("{\"schema_name\": \"Other\", \"body\": {}}");
        assertInvalid(document("{\"$ref\": \"#/key_value_items/0\"}", "", "", ""));
        assertInvalid(document("{\"$ref\": \"https://example.com/x\"}", "", "", ""));
        assertInvalid(document("{\"$ref\": \"#/texts/5\"}", text(0, "text", "body", 1, "a"), "", ""));
        assertInvalid(document("{\"$ref\": \"#/texts/0\"}", text(0, "text", "body", 0, "a"), "", ""));
        // Groups nested deeper than any document: refused, without recursing until the stack is gone.
        StringBuilder groups = new StringBuilder();
        int depth = DoclingDocumentReader.MAX_DEPTH + 5;
        for (int i = 0; i < depth; i++) {
            if (i > 0) groups.append(',');
            String child = i + 1 < depth ? "[{\"$ref\": \"#/groups/" + (i + 1) + "\"}]" : "[]";
            groups.append("{\"self_ref\": \"#/groups/").append(i).append("\", \"children\": ").append(child).append('}');
        }
        assertInvalid(document("{\"$ref\": \"#/groups/0\"}", "", "", groups.toString()));
    }

    /** A group that contains itself is read once, not forever. */
    @Test
    void read_whenAGroupReferencesItself_readsEachItemOnce() {
        String group = "{\"self_ref\": \"#/groups/0\", \"children\": [{\"$ref\": \"#/groups/0\"}, {\"$ref\": \"#/texts/0\"}]}";
        List<Block> blocks = DoclingDocumentReader.read(JSON.readTree(document(
                "{\"$ref\": \"#/groups/0\"}, {\"$ref\": \"#/texts/0\"}", text(0, "text", "body", 1, "bir kez"), "", group)));
        assertThat(blocks).containsExactly(new Block(1, "bir kez", false, false));
    }

    private static void assertInvalid(String json) {
        assertThatThrownBy(() -> DoclingDocumentReader.read(JSON.readTree(json)))
                .isInstanceOfSatisfying(DoclingException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.INVALID_RESPONSE));
    }
}
