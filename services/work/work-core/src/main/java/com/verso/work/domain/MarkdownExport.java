package com.verso.work.domain;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillDefinition.Column;
import com.verso.work.api.dto.SkillDefinition.Section;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.dto.SkillRunResponse;
import com.verso.work.api.enums.SkillCitationKind;
import com.verso.work.api.enums.SkillRunStatus;
import com.verso.work.api.enums.SkillSectionKind;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A run as a Markdown document (ADR-0057): the disclaimer first and last, the sections as tables with the source
 * numbers, "değerlendirilemez" and unverified anchors spelled out, then the source list (file name and page; never a
 * passage text, the reader opens the document with their own access). Cells are escaped so a value cannot break the
 * table or inject markup that renders as a link or an image.
 */
public final class MarkdownExport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private MarkdownExport() {
    }

    public static String render(SkillDefinition skill, SkillRunResponse run, Map<String, String> inputLabels) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(escape(skill.name())).append("\n\n");
        md.append("> ").append(escape(run.disclaimer())).append("\n\n");
        md.append("- Durum: ").append(run.status() == SkillRunStatus.APPROVED ? "Onaylandı" : "Taslak")
                .append(run.edited() ? " (düzenlendi)" : "").append('\n');
        md.append("- Beceri: ").append(skill.id()).append(' ').append(run.skillVersion()).append('\n');
        if (run.createdAt() != null) md.append("- Oluşturma: ").append(DATE.format(run.createdAt())).append('\n');
        md.append("- Yöntem: ").append(escape(skill.framework().name())).append("\n\n");
        Map<String, SkillOutput.Section> sections = run.output().sections().stream()
                .collect(Collectors.toMap(SkillOutput.Section::key, s -> s, (a, b) -> a));
        for (Section schema : skill.output().sections()) {
            SkillOutput.Section s = sections.get(schema.key());
            if (s == null) continue;
            md.append("## ").append(escape(schema.title())).append("\n\n");
            if (schema.kind() == SkillSectionKind.TEXT) {
                md.append(escape(s.text())).append(' ').append(evidence(s.evidence(), s.notAssessable())).append("\n\n");
                continue;
            }
            md.append('|');
            for (Column c : schema.columns()) md.append(' ').append(escape(c.label())).append(" |");
            md.append(" Kaynak |\n|");
            md.append(" --- |".repeat(schema.columns().size() + 1)).append('\n');
            for (SkillOutput.Row row : s.rows()) {
                md.append('|');
                for (Column c : schema.columns()) md.append(' ').append(escape(row.cells().get(c.key()))).append(" |");
                String ev = evidence(row.evidence(), row.notAssessable());
                if (row.unverified() != null && !row.unverified().isEmpty()) {
                    ev += " (kaynakta yok: " + escape(String.join(", ", row.unverified())) + ")";
                }
                md.append(' ').append(ev).append(" |\n");
            }
            md.append('\n');
        }
        md.append("## Kaynaklar\n\n");
        for (SkillRunResponse.Citation c : run.citations()) {
            md.append(c.number()).append(". ");
            if (c.kind() == SkillCitationKind.INPUT) {
                md.append("Girdi: ").append(escape(inputLabels.getOrDefault(c.inputKey(), c.inputKey())));
            } else if (!c.accessible()) {
                md.append("Erişilemeyen belge");
            } else {
                md.append(escape(c.fileName()));
                if (c.page() != null) md.append(", ").append("SECTION".equals(c.unit()) ? "bölüm " : "s. ").append(c.page());
            }
            md.append('\n');
        }
        md.append("\n---\n\n").append(escape(run.disclaimer())).append('\n');
        return md.toString();
    }

    private static String evidence(List<Integer> evidence, Boolean notAssessable) {
        if (Boolean.TRUE.equals(notAssessable)) return "_" + SkillOutput.NOT_ASSESSABLE + "_";
        if (evidence == null || evidence.isEmpty()) return "";
        return evidence.stream().map(n -> "[" + n + "]").collect(Collectors.joining(""));
    }

    /** One line, Markdown control characters escaped (no link, image, HTML or table break from a value). */
    static String escape(String text) {
        if (text == null) return "";
        String flat = text.replace("\r", " ").replace("\n", " ");
        StringBuilder out = new StringBuilder(flat.length());
        for (char ch : flat.toCharArray()) {
            if ("\\`*_{}[]()<>!|~".indexOf(ch) >= 0) out.append('\\');
            out.append(ch);
        }
        // A heading, list or quote marker only works at the start of a line.
        if (!out.isEmpty() && "#+->".indexOf(out.charAt(0)) >= 0) out.insert(0, '\\');
        return out.toString();
    }
}
