package com.verso.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.dto.SystemEntitySummary;
import com.verso.document.api.dto.SystemModelSignal;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.config.SystemModelProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.SystemModelRepository;
import com.verso.document.repository.SystemModelRepository.SignalRow;
import com.verso.document.service.Caller;
import com.verso.platform.security.web.AccountId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The access rule of the system model (ADR-0054, ADR-0028): every read goes to the repository with exactly the caller's
 * principals (the token's sub and groups), and an entity no readable document has is "not found", not "forbidden".
 */
class SystemModelServiceImplTest {

    private final SystemModelRepository repository = mock(SystemModelRepository.class);
    private final SystemModelServiceImpl service = new SystemModelServiceImpl(repository,
            new SystemModelProperties(false, 2, true, Duration.ofMinutes(1), 50), template());

    private static TransactionTemplate template() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TransactionTemplate(manager);
    }

    private static Caller caller(String sub, String... groups) {
        return new Caller(new AccountId(sub), new Reader(sub, List.of(groups)), true);
    }

    @Test
    void lookup_whenCalled_readsWithTheCallersPrincipalsOnly() {
        when(repository.find(any(), anyList(), anyList(), anyInt())).thenReturn(List.of(
                new SystemEntitySummary(SystemEntityType.CODE, "WMS-204", "WMS-204", 1, 1)));
        when(repository.label(any(), eq(SystemEntityType.CODE), eq("WMS-204"))).thenReturn(Optional.of("WMS-204"));

        List<SystemEntityDetail> found = service.lookup(caller("alice", "/depo"), "wms-204 nedir");

        ArgumentCaptor<String[]> principals = ArgumentCaptor.forClass(String[].class);
        verify(repository).find(principals.capture(), anyList(), anyList(), anyInt());
        assertThat(principals.getValue()).containsExactly("user:alice", "group:/depo");
        verify(repository).sources(eq(principals.getValue()), eq(SystemEntityType.CODE), eq("WMS-204"), anyInt());
        assertThat(found).singleElement().extracting(SystemEntityDetail::key).isEqualTo("WMS-204");
    }

    @Test
    void lookup_whenQueryBlankOrTooLong_isRefusedWithoutReading() {
        assertThatThrownBy(() -> service.lookup(caller("alice"), " "))
                .isInstanceOfSatisfying(DocumentServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DocumentErrorCode.SYSTEM_MODEL_QUERY_INVALID));
        assertThat(service.lookup(Reader.personal("alice"), "x".repeat(201), 10)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void entity_whenNoReadableDocumentHasIt_isNotFound() {
        when(repository.label(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.entity(caller("bob"), SystemEntityType.TERM, "FEFO"))
                .isInstanceOfSatisfying(DocumentServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DocumentErrorCode.SYSTEM_ENTITY_NOT_FOUND));
    }

    @Test
    void signals_whenTermDefinitionsDifferOnlyByCaseOrDiacritics_areNoConflict() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.termDefinitions(any(), anyInt())).thenReturn(List.of(
                new SignalRow(SystemEntityType.TERM, "SKT", "SKT", List.of(a, b),
                        List.of("Son Kullanma Tarihi", "son kullanma tarihi")),
                new SignalRow(SystemEntityType.TERM, "FEFO", "FEFO", List.of(a, b),
                        List.of("First Expired First Out", "First Ended First Out"))));

        List<SystemModelSignal> signals = service.signals(Reader.personal("admin"), 10);

        assertThat(signals).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo(SystemModelSignal.Kind.TERM_CONFLICT);
            assertThat(s.key()).isEqualTo("FEFO");
            assertThat(s.values()).hasSize(2);
        });
    }

    @Test
    void browse_whenPrefixGiven_foldsItLikeTheStoredKeys() {
        service.browse(caller("alice"), SystemEntityType.CODE, "ik-", 50);

        verify(repository).browse(any(), eq(SystemEntityType.CODE), eq("IK-"), eq(50));
    }
}
