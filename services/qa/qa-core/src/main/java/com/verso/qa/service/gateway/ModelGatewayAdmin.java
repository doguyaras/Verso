package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;
import com.verso.qa.repository.ModelConnectionRepository;
import com.verso.qa.repository.ModelGatewayRepository;

import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.qa.api.dto.ModelBudget;
import com.verso.qa.api.dto.ModelConnectionRequest;
import com.verso.qa.api.dto.ModelConnectionResponse;
import com.verso.qa.api.dto.ModelConnectionTestResponse;
import com.verso.qa.api.dto.ModelPrice;
import com.verso.qa.api.dto.ModelRouteEntry;
import com.verso.qa.api.dto.ModelRoutingResponse;
import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.api.dto.ModelUsageResponse;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaGatewayProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.service.QaAudit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The admin side of the model gateway (ADR-0056): connections with write-only keys, routes, settings, prices, budgets
 * and the usage report. Every change is one transaction with its audit event (ids, types and fixed words only,
 * ADR-0026/0039); provider calls (the validation on save and rotation) run before it, never inside it (llm-rules 5.1).
 *
 * <p>Mode rules (llm-rules 1.1): in local mode a connection that is not on the internal network is refused at save,
 * enabled or not; the startup check refuses one that got into the database another way; the router and the client
 * factory refuse to use one. In cloud mode every connection's host joins the egress allowlist.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ModelGatewayAdmin {

    private static final Logger log = LoggerFactory.getLogger(ModelGatewayAdmin.class);

    private final ModelConnectionRepository connections;
    private final ModelGatewayRepository repository;
    private final ModelGateway gateway;
    private final QaDatabase db;
    private final QaAudit audit;
    private final VersoAiProperties ai;
    private final QaGatewayProperties properties;
    private final Clock clock;

    public ModelGatewayAdmin(ModelConnectionRepository connections, ModelGatewayRepository repository,
                             ModelGateway gateway, QaDatabase db, QaAudit audit, VersoAiProperties ai,
                             QaGatewayProperties properties, Clock clock) {
        this.connections = connections;
        this.repository = repository;
        this.gateway = gateway;
        this.db = db;
        this.audit = audit;
        this.ai = ai;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------- connections

    public List<ModelConnectionResponse> list() {
        return connections.list().stream().map(ModelConnection::toResponse).toList();
    }

    public ModelConnectionResponse get(UUID id) {
        return find(id).toResponse();
    }

    public ModelConnectionResponse create(ModelConnectionRequest request, String admin) {
        String key = ProviderEndpoints.blankToNull(request.apiKey());
        ProviderEndpoints.validate(request, key != null, false);
        if (key != null) requireKeyStore();
        if (connections.nameTaken(request.name().strip(), null)) {
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_NAME_TAKEN, "NAME_TAKEN");
        }
        boolean enabled = request.enabled() == null || request.enabled();
        ModelConnectionRepository.Settings settings = settings(request, enabled);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        // Validated before anything is stored: a key the provider refuses never reaches the database.
        String status = enabled ? check(transientConnection(id, settings, now), key) : null;
        inTransactionWithUniqueName(() -> {
            connections.insert(id, settings, admin, now);
            audit.connectionCreatedInTransaction(id, request.type().name());
            if (key != null) {
                connections.updateKey(id, 0, gateway.cipher().seal(key, id, 1), EnvelopeCipher.hint(key), 1, now);
            }
            if (status != null) {
                connections.recordValidation(id, status, now);
                audit.connectionTestedInTransaction(id, status);
            }
        });
        gateway.invalidate();
        log.info("Model connection created: connectionId={} type={} locality={} keyStored={}", id, request.type(),
                settings.locality(), key != null);
        return find(id).toResponse();
    }

    /** Settings and, when given, a new key (then a rotation). The key never comes back. */
    public ModelConnectionResponse update(UUID id, ModelConnectionRequest request) {
        ModelConnection current = find(id);
        String key = ProviderEndpoints.blankToNull(request.apiKey());
        if (request.type() != current.type()) {
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_INVALID, "TYPE_CHANGE");
        }
        ProviderEndpoints.validate(request, key != null, current.key() != null);
        if (key != null) requireKeyStore();
        if (connections.nameTaken(request.name().strip(), id)) {
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_NAME_TAKEN, "NAME_TAKEN");
        }
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        ModelConnectionRepository.Settings settings = settings(request, enabled);
        Instant now = clock.instant();
        boolean endpointChanged = !Objects.equals(settings.baseUrl(), current.baseUrl())
                || !Objects.equals(settings.region(), current.region())
                || !Objects.equals(settings.deployment(), current.deployment())
                || !Objects.equals(settings.project(), current.project())
                || !settings.models().getFirst().equals(current.models().getFirst());
        boolean needsCheck = enabled && (key != null || endpointChanged || !current.enabled());
        ModelConnection candidate = new ModelConnection(id, settings.name(), settings.type(), settings.baseUrl(),
                settings.region(), settings.deployment(), settings.project(), settings.models(), enabled,
                settings.locality(), settings.egressHost(), current.key(), current.keyHint(), current.keyVersion(),
                null, null, null, current.createdAt(), now, current.revision());
        String status = needsCheck ? check(candidate, key) : null;
        String detail = enabled != current.enabled() ? (enabled ? "ENABLED" : "DISABLED") : "SETTINGS";
        inTransactionWithUniqueName(() -> {
            if (!connections.updateSettings(id, current.revision(), settings, now)) {
                throw new ServiceException(CommonErrorCode.REQUEST_CONFLICT, "MODEL_CONNECTION_CHANGED");
            }
            audit.connectionUpdatedInTransaction(id, detail);
            if (key != null) storeKey(id, current.revision() + 1, key, current.keyVersion() + 1, now);
            if (status != null) {
                connections.recordValidation(id, status, now);
                audit.connectionTestedInTransaction(id, status);
            }
        });
        gateway.invalidate();
        return find(id).toResponse();
    }

    /** Rotation: the new key is validated (when the connection is on), sealed as the next version, the old one gone. */
    public ModelConnectionResponse rotateKey(UUID id, String newKey) {
        ModelConnection current = find(id);
        if (current.type() == com.verso.qa.api.dto.ModelProviderType.OLLAMA) {
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_INVALID, "KEY_NOT_USED");
        }
        requireKeyStore();
        String key = newKey.strip();
        ProviderEndpoints.validateKey(current.type(), key, ProviderEndpoints.blankToNull(current.project()) != null);
        Instant now = clock.instant();
        String status = current.enabled() ? check(current, key) : null;
        db.inTransaction(tx -> {
            storeKey(id, current.revision(), key, current.keyVersion() + 1, now);
            if (status != null) {
                connections.recordValidation(id, status, now);
                audit.connectionTestedInTransaction(id, status);
            }
            return null;
        });
        gateway.invalidate();
        log.info("Model connection key rotated: connectionId={} keyVersion={}", id, current.keyVersion() + 1);
        return find(id).toResponse();
    }

    private void storeKey(UUID id, long revision, String key, int version, Instant now) {
        if (!connections.updateKey(id, revision, gateway.cipher().seal(key, id, version), EnvelopeCipher.hint(key),
                version, now)) {
            throw new ServiceException(CommonErrorCode.REQUEST_CONFLICT, "MODEL_CONNECTION_CHANGED");
        }
        audit.connectionKeyRotatedInTransaction(id, version);
    }

    /** On request: never fails on the provider's answer, the status says it (and is stored and audited). */
    public ModelConnectionTestResponse test(UUID id) {
        ModelConnection current = find(id);
        requireAllowedInMode(current.type(), current.locality());
        ModelGateway.TestResult result = gateway.test(current, null);
        db.inTransaction(tx -> {
            connections.recordValidation(id, result.status(), clock.instant());
            audit.connectionTestedInTransaction(id, result.status());
            return null;
        });
        return new ModelConnectionTestResponse(result.ok(), result.status(), result.latencyMs());
    }

    public void delete(UUID id) {
        find(id);
        if (repository.connectionRouted(id)) {
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_IN_USE, "ROUTED");
        }
        try {
            db.inTransaction(tx -> {
                repository.deleteBudget("CONNECTION", id.toString());
                if (!connections.delete(id)) throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_NOT_FOUND, "GONE");
                audit.connectionDeletedInTransaction(id);
                return null;
            });
        } catch (DataIntegrityViolationException e) {
            // A route added by another admin in between (the foreign key is RESTRICT).
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_IN_USE, "ROUTED");
        }
        gateway.invalidate();
    }

    // ---------------------------------------------------------------------------------------------- routes

    public ModelRoutingResponse routing() {
        Map<ModelTask, List<ModelRouteEntry>> routes = repository.routes();
        return new ModelRoutingResponse(routes, ai.chatModel(), repository.userModelChoice(),
                gateway.cipher().available(), repository.piiMasking());
    }

    /**
     * Replaces a task's route. Every step names an enabled connection and one of its models (or the built-in model);
     * the same model twice is refused; embeddings have no route (llm-rules 1.2, 6.2).
     */
    public ModelRoutingResponse setRoute(ModelTask task, List<ModelRouteEntry> entries) {
        if (task == ModelTask.EMBEDDING) throw new QaServiceException(QaErrorCode.MODEL_ROUTE_INVALID, "EMBEDDING_PINNED");
        Set<String> seen = new HashSet<>();
        List<ModelRouteEntry> normalised = new ArrayList<>();
        for (ModelRouteEntry entry : entries) {
            if (entry.connectionId() == null) {
                normalised.add(new ModelRouteEntry(null, ai.chatModel()));
            } else {
                ModelConnection c = connections.find(entry.connectionId())
                        .orElseThrow(() -> new QaServiceException(QaErrorCode.MODEL_ROUTE_INVALID, "UNKNOWN_CONNECTION"));
                if (!c.enabled()) throw new QaServiceException(QaErrorCode.MODEL_ROUTE_INVALID, "DISABLED_CONNECTION");
                if (!c.models().contains(entry.model())) {
                    throw new QaServiceException(QaErrorCode.MODEL_ROUTE_INVALID, "UNKNOWN_MODEL");
                }
                if (ai.mode() == AiMode.LOCAL && (!c.type().selfHostable() || c.external())) {
                    throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_LOCAL_MODE, "EXTERNAL_IN_LOCAL_MODE");
                }
                normalised.add(entry);
            }
            ModelRouteEntry last = normalised.getLast();
            if (!seen.add(last.connectionId() + "/" + last.model())) {
                throw new QaServiceException(QaErrorCode.MODEL_ROUTE_INVALID, "DUPLICATE_STEP");
            }
        }
        db.inTransaction(tx -> {
            repository.replaceRoute(task, normalised);
            audit.gatewayChangedInTransaction("ROUTE_" + task.name());
            return null;
        });
        gateway.invalidate();
        return routing();
    }

    /**
     * The gateway's switches; a null leaves that one unchanged. Turning personal data masking on or off (ADR-0059) is
     * its own audit word, so the trail shows when cloud calls started carrying raw values.
     */
    public ModelRoutingResponse setSettings(Boolean userModelChoice, Boolean piiMasking) {
        db.inTransaction(tx -> {
            if (userModelChoice != null) {
                repository.setUserModelChoice(userModelChoice, clock.instant());
                audit.gatewayChangedInTransaction("SETTINGS");
            }
            if (piiMasking != null) {
                repository.setPiiMasking(piiMasking, clock.instant());
                audit.gatewayChangedInTransaction(piiMasking ? "PII_MASKING_ON" : "PII_MASKING_OFF");
            }
            return null;
        });
        if (Boolean.FALSE.equals(piiMasking)) {
            log.warn("Model gateway personal data masking turned off: code=PII_MASKING_OFF");
        }
        gateway.invalidate();
        return routing();
    }

    // ---------------------------------------------------------------------------------------------- prices, budgets

    public List<ModelPrice> prices() {
        return repository.prices();
    }

    public ModelPrice setPrice(ModelPrice price) {
        ModelConnection c = connections.find(price.connectionId())
                .orElseThrow(() -> new QaServiceException(QaErrorCode.MODEL_PRICE_INVALID, "UNKNOWN_CONNECTION"));
        if (!c.models().contains(price.model())) {
            throw new QaServiceException(QaErrorCode.MODEL_PRICE_INVALID, "UNKNOWN_MODEL");
        }
        db.inTransaction(tx -> {
            repository.upsertPrice(price);
            audit.gatewayChangedInTransaction("PRICE");
            return null;
        });
        gateway.invalidate();
        return price;
    }

    public void deletePrice(UUID connectionId, String model) {
        db.inTransaction(tx -> {
            if (!repository.deletePrice(connectionId, model)) {
                throw new QaServiceException(QaErrorCode.MODEL_PRICE_INVALID, "UNKNOWN_PRICE");
            }
            audit.gatewayChangedInTransaction("PRICE");
            return null;
        });
        gateway.invalidate();
    }

    public List<ModelBudget> budgets() {
        return repository.budgets().stream().map(ModelGatewayRepository.BudgetRow::toDto).toList();
    }

    public ModelBudget setBudget(ModelBudget budget) {
        if ("CONNECTION".equals(budget.scope())) {
            UUID id;
            try {
                id = UUID.fromString(budget.scopeKey());
            } catch (IllegalArgumentException e) {
                throw new QaServiceException(QaErrorCode.MODEL_PRICE_INVALID, "UNKNOWN_CONNECTION");
            }
            connections.find(id).orElseThrow(() -> new QaServiceException(QaErrorCode.MODEL_PRICE_INVALID,
                    "UNKNOWN_CONNECTION"));
        }
        if (budget.softLimit() != null && budget.softLimit().compareTo(budget.hardLimit()) > 0) {
            throw new QaServiceException(QaErrorCode.MODEL_PRICE_INVALID, "SOFT_ABOVE_HARD");
        }
        db.inTransaction(tx -> {
            repository.upsertBudget(budget);
            audit.gatewayChangedInTransaction("BUDGET");
            return null;
        });
        gateway.invalidate();
        return budget;
    }

    public void deleteBudget(String scope, String key) {
        db.inTransaction(tx -> {
            if (!repository.deleteBudget(scope, key)) {
                throw new QaServiceException(QaErrorCode.MODEL_PRICE_INVALID, "UNKNOWN_BUDGET");
            }
            audit.gatewayChangedInTransaction("BUDGET");
            return null;
        });
        gateway.invalidate();
    }

    // ---------------------------------------------------------------------------------------------- usage

    /**
     * One month's usage: per connection, model and task; per team only with at least {@code min-team-users} distinct
     * users (keyed digests, so without the KEK no team row is shown at all).
     */
    public ModelUsageResponse usage(String month) {
        String wanted = month == null ? gateway.month() : month;
        try {
            YearMonth parsed = YearMonth.parse(wanted);
            if (parsed.getYear() < 2020 || parsed.getYear() > 2100) throw new DateTimeParseException("range", wanted, 0);
        } catch (DateTimeParseException e) {
            throw new QaServiceException(QaErrorCode.MODEL_USAGE_RANGE_INVALID, "MONTH");
        }
        List<ModelUsageResponse.Row> rows = repository.usageRows(wanted);
        List<ModelUsageResponse.Team> teams = new ArrayList<>();
        int hidden = 0;
        for (ModelGatewayRepository.TeamUsage t : repository.teamUsage(wanted)) {
            if (t.users() >= properties.minTeamUsers()) teams.add(t.team());
            else hidden++;
        }
        long requests = rows.stream().mapToLong(ModelUsageResponse.Row::requests).sum();
        long prompt = rows.stream().mapToLong(ModelUsageResponse.Row::promptTokens).sum();
        long completion = rows.stream().mapToLong(ModelUsageResponse.Row::completionTokens).sum();
        BigDecimal cost = rows.stream().map(ModelUsageResponse.Row::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ModelUsageResponse.BudgetState> states = new ArrayList<>();
        for (ModelGatewayRepository.BudgetRow b : repository.budgets()) {
            BigDecimal spent = "CONNECTION".equals(b.scope())
                    ? repository.spentByConnection(wanted, UUID.fromString(b.scopeKey()))
                    : repository.spentByTeam(wanted, b.scopeKey());
            String state = spent.compareTo(b.hardLimit()) >= 0 ? "HARD"
                    : b.softLimit() != null && spent.compareTo(b.softLimit()) >= 0 ? "SOFT" : "OK";
            states.add(new ModelUsageResponse.BudgetState(b.scope(), b.scopeKey(), b.hardLimit(), b.softLimit(),
                    spent, state));
        }
        Map<String, Long> masked = repository.maskedByType(wanted);
        long maskedValues = masked.values().stream().mapToLong(Long::longValue).sum();
        audit.modelUsageViewed();
        return new ModelUsageResponse(wanted, requests, prompt, completion, cost, rows, teams, hidden,
                properties.minTeamUsers(), states, maskedValues, masked);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private ModelConnectionRepository.Settings settings(ModelConnectionRequest r, boolean enabled) {
        String baseUrl = ProviderEndpoints.blankToNull(r.baseUrl());
        String host = ProviderEndpoints.egressHost(r.type(), baseUrl, ProviderEndpoints.blankToNull(r.region()),
                ProviderEndpoints.blankToNull(r.project()));
        ModelConnection.Locality locality = ProviderEndpoints.locality(r.type(), host);
        requireAllowedInMode(r.type(), locality);
        return new ModelConnectionRepository.Settings(r.name().strip(), r.type(), baseUrl,
                ProviderEndpoints.blankToNull(r.region()), ProviderEndpoints.blankToNull(r.deployment()),
                ProviderEndpoints.blankToNull(r.project()), r.models().stream().map(String::strip).distinct().toList(),
                enabled, locality, host);
    }

    /** Local mode: only a self-hostable type whose host is on the internal network, enabled or not (llm-rules 1.1). */
    private void requireAllowedInMode(com.verso.qa.api.dto.ModelProviderType type, ModelConnection.Locality locality) {
        if (ai.mode() == AiMode.LOCAL && (!type.selfHostable() || locality != ModelConnection.Locality.LOCAL)) {
            log.warn("Model connection rejected: code=MODEL_CONNECTION_LOCAL_MODE reason={}",
                    type.selfHostable() ? "ADDRESS_NOT_INTERNAL" : "CLOUD_PROVIDER");
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_LOCAL_MODE,
                    type.selfHostable() ? "ADDRESS_NOT_INTERNAL" : "CLOUD_PROVIDER");
        }
    }

    /** A validation call; anything but OK refuses the save (422) and is audited against the id. */
    private String check(ModelConnection connection, String key) {
        ModelGateway.TestResult result = gateway.test(connection, key);
        if (!result.ok()) {
            db.inTransaction(tx -> {
                audit.connectionTestedInTransaction(connection.id(), result.status());
                return null;
            });
            log.warn("Model connection rejected: code=MODEL_CONNECTION_CHECK_FAILED reason={}", result.status());
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_CHECK_FAILED, result.status());
        }
        return result.status();
    }

    private static ModelConnection transientConnection(UUID id, ModelConnectionRepository.Settings s, Instant now) {
        return new ModelConnection(id, s.name(), s.type(), s.baseUrl(), s.region(), s.deployment(), s.project(),
                s.models(), s.enabled(), s.locality(), s.egressHost(), null, null, 0, null, null, null, now, now, 0);
    }

    private void requireKeyStore() {
        if (!gateway.cipher().available()) {
            log.warn("Model connection rejected: code=MODEL_KEY_STORE_UNAVAILABLE reason=KEK_MISSING");
            throw new QaServiceException(QaErrorCode.MODEL_KEY_STORE_UNAVAILABLE, "KEK_MISSING");
        }
    }

    private ModelConnection find(UUID id) {
        return connections.find(id)
                .orElseThrow(() -> new QaServiceException(QaErrorCode.MODEL_CONNECTION_NOT_FOUND, "UNKNOWN"));
    }

    /** The unique name index is the real guard: another admin may take the name between the check and the insert. */
    private void inTransactionWithUniqueName(Runnable work) {
        try {
            db.inTransaction(tx -> {
                work.run();
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw new QaServiceException(QaErrorCode.MODEL_CONNECTION_NAME_TAKEN, "NAME_TAKEN");
        }
    }
}
