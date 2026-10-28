package com.verso.work.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.dto.SkillRunEditRequest;
import com.verso.work.api.dto.SkillRunRequest;
import com.verso.work.api.dto.SkillRunResponse;
import com.verso.work.api.dto.SkillRunSummary;
import com.verso.work.api.enums.SkillRunStatus;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.NamedInterface;

/**
 * The work contract (ADR-0057): every package is part of the named interface "api", no field can carry a credential,
 * the run request never prints the caller's inputs, and the history line carries no input or output text.
 */
class WorkApiContractTest {

    @Test
    void packages_whenInspected_areNamedInterfaces() {
        for (Package p : List.of(WorkSubjects.class.getPackage(), SkillDefinition.class.getPackage(),
                SkillRunStatus.class.getPackage())) {
            assertThat(p.getAnnotation(NamedInterface.class)).as(p.getName()).isNotNull();
            assertThat(p.getAnnotation(NamedInterface.class).value()).containsExactly("api");
        }
    }

    @Test
    void records_whenInspected_haveNoFieldThatCouldHoldACredential() {
        for (Class<?> type : List.of(SkillDefinition.class, SkillDefinition.Grounding.class, SkillRunRequest.class,
                SkillRunResponse.class, SkillRunResponse.Citation.class, SkillRunSummary.class, SkillOutput.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                assertThat(name).as(type.getSimpleName() + "." + component.getName())
                        .doesNotContain("password").doesNotContain("secret").doesNotContain("token");
            }
        }
    }

    @Test
    void runRequest_whenPrinted_showsNoInputValue() {
        SkillRunRequest request = new SkillRunRequest(Map.of("notes", "MarkerSecretNote 42"));
        assertThat(request.toString()).doesNotContain("MarkerSecretNote").contains("fields=1");
        assertThat(new SkillRunEditRequest(new SkillOutput(List.of())).toString()).isEqualTo("SkillRunEditRequest[]");
    }

    @Test
    void summary_whenInspected_carriesNoInputOrOutput() {
        List<String> names = java.util.Arrays.stream(SkillRunSummary.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(names).doesNotContain("inputs", "output", "citations");
    }

    @Test
    void notAssessable_isTheTurkishWord() {
        assertThat(SkillOutput.NOT_ASSESSABLE).isEqualTo("değerlendirilemez");
    }
}
