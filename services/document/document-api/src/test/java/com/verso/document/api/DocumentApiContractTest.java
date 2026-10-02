package com.verso.document.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.NamedInterface;

/** The contract module's own rules (ADR-0007 #24, reference 3.3): exposed packages and immutable value types. */
class DocumentApiContractTest {

    @Test
    void packages_whenCompiled_areAllNamedInterfaceApi() throws Exception {
        for (String name : List.of("com.verso.document.api", "com.verso.document.api.dto", "com.verso.document.api.enums")) {
            NamedInterface named = Class.forName(name + ".package-info").getAnnotation(NamedInterface.class);
            assertThat(named).as(name).isNotNull();
            assertThat(named.value()).as(name).containsExactly("api");
        }
    }

    @Test
    void types_whenExposed_areRecordsOrEnums() {
        for (Class<?> type : List.of(DocumentResponse.class, PageResponse.class, PageResponse.PageInfo.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
        }
        assertThat(DocumentStatus.class.isEnum()).isTrue();
        assertThat(DocumentFailureReason.class.isEnum()).isTrue();
    }

    @Test
    void pageResponse_whenBuilt_computesTotalPagesAndCopiesData() {
        PageResponse<String> page = PageResponse.of(new java.util.ArrayList<>(List.of("a", "b")), 1, 2, 5);
        assertThat(page.page()).isEqualTo(new PageResponse.PageInfo(1, 2, 5, 3));
        assertThat(page.data()).containsExactly("a", "b");
        assertThat(PageResponse.of(List.of(), 0, 20, 0).page().totalPages()).isZero();
    }
}
