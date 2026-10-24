package com.verso.privacy.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.privacy.api.dto.AnswerRedactionRequest;
import com.verso.privacy.api.dto.AnswerRedactionResponse;
import com.verso.privacy.api.dto.SubjectDeletionRequest;
import com.verso.privacy.api.dto.SubjectDeletionResponse;
import com.verso.privacy.api.dto.SubjectDocumentItem;
import com.verso.privacy.api.dto.SubjectDocuments;
import com.verso.privacy.api.dto.SubjectHistory;
import com.verso.privacy.api.dto.SubjectSummaryResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.NamedInterface;

/** The contract module's rules (ADR-0007 #24) and the privacy of its records' string forms (ADR-0034). */
class PrivacyApiContractTest {

    @Test
    void packages_whenCompiled_areAllNamedInterfaceApi() throws Exception {
        Set<String> packages = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/verso/privacy/api/**/*.class")) {
            String path = resource.getURL().toString();
            String relative = path.substring(path.indexOf("com/verso/privacy/api"));
            packages.add(relative.substring(0, relative.lastIndexOf('/')).replace('/', '.'));
        }
        assertThat(packages).contains("com.verso.privacy.api.dto");
        for (String name : packages) {
            if (name.equals("com.verso.privacy.api")) continue; // test classes only
            NamedInterface named = Class.forName(name + ".package-info").getAnnotation(NamedInterface.class);
            assertThat(named).as(name).isNotNull();
            assertThat(named.value()).as(name).containsExactly("api");
        }
        assertThat(Class.forName("com.verso.privacy.api.package-info").getAnnotation(NamedInterface.class)).isNotNull();
    }

    @Test
    void types_whenExposed_areRecords() {
        for (Class<?> type : List.of(SubjectSummaryResponse.class, SubjectHistory.class, SubjectDocuments.class,
                SubjectDocumentItem.class, SubjectDeletionRequest.class, com.verso.privacy.api.dto.SubjectRequest.class, SubjectDeletionResponse.class,
                SubjectDeletionResponse.Kept.class, AnswerRedactionRequest.class, AnswerRedactionResponse.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
        }
    }

    /** A record printed by mistake must not carry a file name or the subject's sub into a log line. */
    @Test
    void stringForms_whenPrinted_holdNoNameAndNoSub() {
        SubjectDocumentItem item = new SubjectDocumentItem(UUID.randomUUID(), "maas-bordrosu-ayse.pdf", "PDF", "READY", 10,
                null, Instant.now());
        assertThat(item.toString()).doesNotContain("ayse");
        assertThat(new SubjectDeletionRequest("acct-ayse", "acct-ayse").toString()).doesNotContain("ayse");
        assertThat(new com.verso.privacy.api.dto.SubjectRequest("acct-ayse").toString()).doesNotContain("ayse");
        SubjectDocuments documents = new SubjectDocuments(1, List.of(item), 0, List.of(), List.of(), List.of(), false);
        SubjectDeletionResponse response = new SubjectDeletionResponse(new SubjectHistory(1, 2, 0, 1), 1, 0,
                new SubjectDeletionResponse.Kept(documents, 3));
        assertThat(response.toString()).doesNotContain("ayse");
    }

    @Test
    void documents_whenBuilt_copyTheirLists() {
        List<SubjectDocumentItem> mutable = new ArrayList<>();
        SubjectDocuments documents = new SubjectDocuments(0, mutable, 0, List.of(), List.of(), List.of(), false);
        mutable.add(new SubjectDocumentItem(UUID.randomUUID(), "a", "PDF", "READY", 1, null, Instant.now()));
        assertThat(documents.personal()).isEmpty();
    }
}
