package com.verso.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.connector.api.dto.ConnectorItemResponse;
import com.verso.connector.api.dto.ConnectorPageResponse;
import com.verso.connector.api.dto.ConnectorRequest;
import com.verso.connector.api.dto.ConnectorResponse;
import com.verso.connector.api.dto.SyncRequest;
import com.verso.connector.api.dto.SyncRunResponse;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.NamedInterface;

/**
 * The connector contract (ADR-0038): no field can carry a password, the request names the secret file only by a
 * {@code SECRET_CONNECTOR_*} name, and every package is part of the named interface "api".
 */
class ConnectorApiContractTest {

    private static final List<Class<?>> RECORDS = List.of(ConnectorRequest.class, ConnectorResponse.class,
            ConnectorResponse.ItemCounts.class, SyncRequest.class, SyncRunResponse.class, ConnectorItemResponse.class,
            ConnectorPageResponse.class, ConnectorPageResponse.PageInfo.class,
            com.verso.connector.api.dto.ConfluenceRequest.class, com.verso.connector.api.dto.ConfluenceResponse.class);

    @Test
    void records_whenInspected_haveNoFieldThatCouldHoldAPassword() {
        for (Class<?> type : RECORDS) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                assertThat(name).as(type.getSimpleName() + "." + component.getName())
                        .doesNotContain("password").doesNotContain("secret").doesNotContain("token");
                if (name.contains("credential")) assertThat(component.getName()).isEqualTo("credentialRef");
            }
        }
    }

    @Test
    void request_whenNamingTheSecret_acceptsOnlyConnectorSecretNames() throws Exception {
        jakarta.validation.constraints.Pattern pattern = ConnectorRequest.class.getRecordComponents()[
                Arrays.stream(ConnectorRequest.class.getRecordComponents()).map(RecordComponent::getName).toList()
                        .indexOf("credentialRef")].getAccessor().getDeclaringClass()
                .getDeclaredField("credentialRef").getAnnotation(jakarta.validation.constraints.Pattern.class);
        assertThat(pattern).isNotNull();
        assertThat("SECRET_CONNECTOR_SMB_PASSWORD").matches(pattern.regexp());
        assertThat("SECRET_DB_DOCUMENT_PASSWORD").doesNotMatch(pattern.regexp());
        assertThat("spring.datasource.password").doesNotMatch(pattern.regexp());
    }

    @Test
    void packages_whenInspected_areNamedInterfaces() {
        for (Package p : List.of(ConnectorRequest.class.getPackage(),
                com.verso.connector.api.enums.ConnectorHealth.class.getPackage())) {
            assertThat(p.getAnnotation(NamedInterface.class)).as(p.getName()).isNotNull();
            assertThat(p.getAnnotation(NamedInterface.class).value()).containsExactly("api");
        }
    }

    @Test
    void page_whenBuilt_countsPages() {
        ConnectorPageResponse<String> page = ConnectorPageResponse.of(List.of("a"), 0, 2, 3);
        assertThat(page.page().totalPages()).isEqualTo(2);
    }
}
