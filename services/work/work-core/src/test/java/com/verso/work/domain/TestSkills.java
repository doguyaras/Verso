package com.verso.work.domain;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillDefinition.Column;
import com.verso.work.api.dto.SkillDefinition.Input;
import com.verso.work.api.dto.SkillDefinition.Section;
import com.verso.work.api.dto.SkillDefinition.Step;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.enums.SkillCategory;
import com.verso.work.api.enums.SkillInputType;
import com.verso.work.api.enums.SkillSectionKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small valid skill for the domain tests, and an output that fits any skill's schema. */
public final class TestSkills {

    private TestSkills() {
    }

    public static SkillDefinition valid() {
        return new SkillDefinition(1, "test-skill", "1.0.0", "Test becerisi", "Toplantı notlarından karar listesi çıkarır.",
                SkillCategory.PRODUCTIVITY, "test",
                List.of(new Input("notes", "Notlar", SkillInputType.LONG_TEXT, true, 5000, null, null, null, null),
                        new Input("tone", "Ton", SkillInputType.SELECT, false, null, null,
                                List.of(new SkillDefinition.Option("resmi", "Resmî"),
                                        new SkillDefinition.Option("sade", "Sade")), null, null),
                        new Input("docs", "Belgeler", SkillInputType.DOCUMENTS, false, null, 3, null, null, null)),
                new SkillDefinition.Grounding(List.of("{{notes}}"), 4, "docs", null, null),
                List.of(new Step("notlar", "Notları oku", "Notlardaki kararları ve aksiyonları belirle.", 1000),
                        new Step("yaz", "Tabloyu yaz", "Her kararı tek satır yaz; kaynak yoksa değerlendirilemez de.", null)),
                new SkillDefinition.Output(List.of(
                        new Section("decisions", "Kararlar", SkillSectionKind.ROWS,
                                List.of(new Column("decision", "Karar", 200, null),
                                        new Column("level", "Önem", 10, List.of("Yüksek", "Orta", "Düşük"))),
                                0, 5, null),
                        new Section("summary", "Özet", SkillSectionKind.TEXT, null, null, null, 300))),
                2000,
                List.of(new SkillDefinition.RubricDimension("groundedness", "Dayanaklılık", "Her satır kaynağa dayanır."),
                        new SkillDefinition.RubricDimension("coverage", "Kapsam", "Bütün kararlar listelenir."),
                        new SkillDefinition.RubricDimension("length", "Uzunluk", "Kısa ve öz yazılır.")),
                new SkillDefinition.Framework("Toplantı tutanağı", "ISO 9001:2015 7.5"),
                "Taslak — sorumlu kişinin incelemesi için.", false, null, List.of("kesinlikle doğrudur"));
    }

    /** One row (citing source 1, or değerlendirilemez without sources) per ROWS section and a text per TEXT section. */
    public static SkillOutput fitting(SkillDefinition skill, int sourceCount) {
        List<SkillOutput.Section> sections = new ArrayList<>();
        boolean any = sourceCount > 0;
        for (Section s : skill.output().sections()) {
            if (s.kind() == SkillSectionKind.TEXT) {
                sections.add(new SkillOutput.Section(s.key(), "Kısa özet.", any ? List.of(1) : List.of(), !any, null));
            } else {
                List<SkillOutput.Row> rows = new ArrayList<>();
                for (int i = 0; i < Math.max(1, s.minRows()); i++) {
                    Map<String, String> cells = new LinkedHashMap<>();
                    for (Column c : s.columns()) cells.put(c.key(), c.allowed() != null ? c.allowed().getFirst() : "Değer");
                    rows.add(new SkillOutput.Row(cells, any ? List.of(1) : List.of(), !any, List.of()));
                }
                sections.add(new SkillOutput.Section(s.key(), null, null, null, rows));
            }
        }
        return new SkillOutput(sections);
    }
}
