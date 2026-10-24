package com.verso.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.audit.api.dto.AuditEntryResponse;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditPageResponse;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.NamedInterface;

/** The contract module's rules (ADR-0007 #24) and the "ids only" guarantee of the event (ADR-0026). */
class AuditApiContractTest {

    @Test
    void packages_whenCompiled_areAllNamedInterfaceApi() throws Exception {
        Set<String> packages = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:com/verso/audit/api/**/*.class")) {
            String path = resource.getURL().toString();
            String relative = path.substring(path.indexOf("com/verso/audit/api"));
            packages.add(relative.substring(0, relative.lastIndexOf('/')).replace('/', '.'));
        }
        assertThat(packages).contains("com.verso.audit.api", "com.verso.audit.api.dto", "com.verso.audit.api.enums");
        for (String name : packages) {
            NamedInterface named = Class.forName(name + ".package-info").getAnnotation(NamedInterface.class);
            assertThat(named).as(name).isNotNull();
            assertThat(named.value()).as(name).containsExactly("api");
        }
    }

    @Test
    void types_whenExposed_areRecords() {
        for (Class<?> type : List.of(AuditEvent.class, AuditEntryResponse.class, AuditPageResponse.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
        }
    }

    /** The event has no component that could carry content: only enums, UUIDs and validated fixed tokens. */
    @Test
    void event_whenInspected_hasNoFreeTextField() {
        for (var component : AuditEvent.class.getRecordComponents()) {
            Class<?> type = component.getType();
            boolean allowed = type.isEnum() || type == UUID.class || type == List.class
                    || (type == String.class && Set.of("detailCode", "modelId", "mode").contains(component.getName()));
            assertThat(allowed).as("component " + component.getName()).isTrue();
        }
    }

    @Test
    void event_whenGivenSentencesOrOddTokens_isRefused() {
        AuditEvent base = AuditEvent.success(AuditAction.QUESTION_ASKED, AuditTargetType.TURN, UUID.randomUUID());
        assertThatThrownBy(() -> base.withDetail("what is the salary of Ayse?")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withDetail("answered")).as("lower case").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withDetail("A".repeat(41))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.answeredBy("my file.pdf", "local", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.answeredBy("m", "hybrid", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(base.withDetail("ANSWERED").answeredBy("gemma4:e2b", "local", List.of()).modelId()).isEqualTo("gemma4:e2b");
        assertThat(base.answeredBy("claude-sonnet-5-5", "cloud", List.of()).mode()).isEqualTo("cloud");
    }

    @Test
    void event_whenItCitesManyDocuments_keepsDistinctOnesUpToTheCap() {
        UUID doc = UUID.randomUUID();
        AuditEvent base = AuditEvent.success(AuditAction.QUESTION_ASKED, AuditTargetType.TURN);
        assertThat(base.answeredBy("m", "local", List.of(doc, doc, doc)).citationDocumentIds()).containsExactly(doc);
        List<UUID> many = IntStream.range(0, 80).mapToObj(i -> UUID.randomUUID()).toList();
        assertThat(base.answeredBy("m", "local", many).citationDocumentIds()).hasSize(AuditEvent.MAX_CITATIONS);
        assertThat(base.citationDocumentIds()).isEmpty();
        assertThat(base.answeredBy("m", "local", null).citationDocumentIds()).isEmpty();
    }

    @Test
    void page_whenBuilt_hasTheSharedListShape() {
        AuditPageResponse page = AuditPageResponse.of(List.of(), 2, 50, 101);
        assertThat(page.page().totalPages()).isEqualTo(3);
        assertThat(page.page().number()).isEqualTo(2);
    }
}
