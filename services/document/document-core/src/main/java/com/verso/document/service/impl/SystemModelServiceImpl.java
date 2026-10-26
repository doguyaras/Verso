package com.verso.document.service.impl;

import com.verso.document.api.DocumentSystemModel;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SystemDefinition;
import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.dto.SystemEntitySummary;
import com.verso.document.api.dto.SystemLink;
import com.verso.document.api.dto.SystemModelSignal;
import com.verso.document.api.dto.SystemModelStats;
import com.verso.document.api.dto.SystemSource;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.config.SystemModelProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.SystemModelRepository;
import com.verso.document.repository.SystemModelRepository.SignalRow;
import com.verso.document.service.Caller;
import com.verso.document.service.SystemModelService;
import com.verso.document.systemmodel.QuestionKeys;
import com.verso.document.systemmodel.SystemModelExtractor;
import com.verso.platform.core.text.TurkishText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The system model's reads (ADR-0054) for the admin map, the lookup API, MCP verso_lookup and the knowledge-health
 * report. Each call is one short read-only transaction; every statement filters on the caller's principals (ADR-0028).
 * Bounded everywhere: a list that hit its bound is reported as truncated. No model call, nothing logged but counts.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SystemModelServiceImpl implements SystemModelService, DocumentSystemModel {

    static final int MAX_BROWSE = 200;
    static final int DEFINITIONS = 10;
    static final int LINKS = 60;
    static final int SOURCES = 20;
    static final int LOOKUP_SOURCES = 5;
    static final int DOCUMENT_ENTITIES = 500;
    static final int MAX_SIGNALS = 50;
    /** Entity types a lookup may name; a step is reached through its process. */
    static final List<SystemEntityType> LOOKUP_TYPES = Arrays.stream(SystemEntityType.values())
            .filter(t -> t != SystemEntityType.STEP).toList();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SystemModelServiceImpl.class);

    private final SystemModelRepository repository;
    private final SystemModelProperties properties;
    private final TransactionTemplate readOnly;

    public SystemModelServiceImpl(SystemModelRepository repository, SystemModelProperties properties,
                                  TransactionTemplate transaction) {
        this.repository = repository;
        this.properties = properties;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    // --- SystemModelService (controllers)

    @Override
    public List<SystemEntitySummary> browse(Caller caller, SystemEntityType type, String prefix, int limit) {
        String folded = prefix == null ? "" : fold(prefix, type);
        int bounded = Math.max(1, Math.min(limit, MAX_BROWSE));
        return read(() -> repository.browse(caller.principals(), type, folded, bounded));
    }

    @Override
    public SystemEntityDetail entity(Caller caller, SystemEntityType type, String key) {
        if (key == null || key.isBlank() || key.length() > DocumentSystemModel.MAX_QUERY_LENGTH) {
            throw rejected(DocumentErrorCode.SYSTEM_ENTITY_NOT_FOUND, "KEY_INVALID");
        }
        SystemEntityDetail detail = read(() -> detail(caller.principals(), type, key, SOURCES));
        if (detail == null) throw rejected(DocumentErrorCode.SYSTEM_ENTITY_NOT_FOUND, "NOT_READABLE");
        return detail;
    }

    @Override
    public List<SystemEntityDetail> lookup(Caller caller, String query) {
        if (query == null || query.isBlank() || query.length() > DocumentSystemModel.MAX_QUERY_LENGTH) {
            throw rejected(DocumentErrorCode.SYSTEM_MODEL_QUERY_INVALID, "QUERY_INVALID");
        }
        return lookup(caller.reader(), query, DocumentSystemModel.MAX_LOOKUP);
    }

    @Override
    public SystemModelStats stats(Caller caller) {
        return read(() -> {
            String[] principals = caller.principals();
            long[] documents = repository.documentCounts(principals, SystemModelExtractor.VERSION);
            return new SystemModelStats(repository.entityCounts(principals), documents[0], documents[1],
                    SystemModelExtractor.VERSION, properties.enrichmentEnabled());
        });
    }

    // --- DocumentSystemModel (in-process contract: qa, mcp)

    @Override
    public List<SystemEntityDetail> lookup(Reader reader, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) return List.of();
        List<String> keys = QuestionKeys.of(query);
        int bounded = Math.max(1, Math.min(limit, MAX_LOOKUP));
        String[] principals = reader.principals().toArray(String[]::new);
        return read(() -> {
            List<SystemEntityDetail> found = new ArrayList<>();
            for (SystemEntitySummary summary : repository.find(principals, LOOKUP_TYPES, keys, bounded)) {
                SystemEntityDetail detail = detail(principals, summary.type(), summary.key(), LOOKUP_SOURCES);
                if (detail != null) found.add(detail);
            }
            return List.copyOf(found);
        });
    }

    @Override
    public List<SystemEntitySummary> documentEntities(Reader reader, UUID documentId) {
        if (documentId == null) return List.of();
        return read(() -> repository.documentEntities(reader.principals().toArray(String[]::new), documentId,
                DOCUMENT_ENTITIES));
    }

    @Override
    public List<SourceChunk> documentChunks(Reader reader, UUID documentId, int limit) {
        if (documentId == null || limit <= 0) return List.of();
        return read(() -> repository.documentChunks(reader.principals().toArray(String[]::new), documentId,
                Math.min(limit, 200)));
    }

    @Override
    public List<SystemModelSignal> signals(Reader admin, int limit) {
        String[] principals = admin.principals().toArray(String[]::new);
        int bounded = Math.max(1, Math.min(limit, MAX_SIGNALS));
        return read(() -> {
            List<SystemModelSignal> signals = new ArrayList<>();
            for (SignalRow row : repository.undefinedCodes(principals, bounded)) {
                signals.add(signal(SystemModelSignal.Kind.UNDEFINED_CODE, row, List.of()));
            }
            // Over-read: the database compares lower(), the Turkish folding may merge more of them.
            for (SignalRow row : repository.termDefinitions(principals, bounded * 2)) {
                List<String> distinct = distinctFolded(row.values());
                if (distinct.size() > 1 && signals.stream()
                        .filter(s -> s.kind() == SystemModelSignal.Kind.TERM_CONFLICT).count() < bounded) {
                    signals.add(signal(SystemModelSignal.Kind.TERM_CONFLICT, row, distinct));
                }
            }
            for (SignalRow row : repository.processesWithoutOwner(principals, bounded)) {
                signals.add(signal(SystemModelSignal.Kind.PROCESS_WITHOUT_OWNER, row, List.of()));
            }
            return List.copyOf(signals);
        });
    }

    // --- helpers

    /** The entity as the reader may see it, or null when no readable READY document has it. */
    private SystemEntityDetail detail(String[] principals, SystemEntityType type, String key, int sourceLimit) {
        String label = repository.label(principals, type, key).orElse(null);
        if (label == null) return null;
        List<SystemDefinition> definitions = repository.definitions(principals, type, key, DEFINITIONS + 1);
        List<SystemLink> links = repository.links(principals, type, key, LINKS + 1);
        List<SystemSource> sources = repository.sources(principals, type, key, sourceLimit + 1);
        boolean truncated = definitions.size() > DEFINITIONS || links.size() > LINKS || sources.size() > sourceLimit;
        return new SystemEntityDetail(type, key, label, cut(definitions, DEFINITIONS), cut(links, LINKS),
                cut(sources, sourceLimit), truncated);
    }

    private static SystemModelSignal signal(SystemModelSignal.Kind kind, SignalRow row, List<String> values) {
        return new SystemModelSignal(kind, row.type(), row.key(), row.label(), row.documentIds(), values);
    }

    /** One value per Turkish-folded text, in order; "First Expired First Out" and "first expired first out" are one. */
    static List<String> distinctFolded(List<String> values) {
        Map<String, String> byFold = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null) continue;
            byFold.putIfAbsent(TurkishText.fold(value).replaceAll("[^\\p{L}\\p{N}]+", " ").strip(), value);
        }
        return List.copyOf(byFold.values());
    }

    /** The key form the type stores: codes, terms and system acronyms upper case, everything else lower case. */
    private static String fold(String prefix, SystemEntityType type) {
        String text = prefix.strip();
        if (type == SystemEntityType.CODE || type == SystemEntityType.TERM) return TurkishText.foldUpper(text);
        if (type == SystemEntityType.SYSTEM && text.equals(text.toUpperCase(TurkishText.TURKISH))) {
            return TurkishText.foldUpper(text);
        }
        return TurkishText.fold(text);
    }

    /** The one structured line before a user-caused rejection (AGENTS.md 11): a fixed reason, never the query. */
    private static DocumentServiceException rejected(DocumentErrorCode code, String reason) {
        log.warn("System model request rejected: code={} reason={}", code.getCode(), reason);
        return new DocumentServiceException(code);
    }

    private static <T> List<T> cut(List<T> list, int limit) {
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private <T> T read(java.util.function.Supplier<T> body) {
        return readOnly.execute(status -> body.get());
    }
}
