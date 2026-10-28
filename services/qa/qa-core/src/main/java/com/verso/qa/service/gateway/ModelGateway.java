package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;
import com.verso.qa.service.ModelRouter;
import com.verso.qa.repository.ModelConnectionRepository;
import com.verso.qa.repository.ModelGatewayRepository;

import com.verso.platform.security.authority.CallerGroups;
import com.verso.qa.api.dto.ModelChoicesResponse;
import com.verso.qa.api.dto.ModelPrice;
import com.verso.qa.api.dto.ModelRouteEntry;
import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaGatewayProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.QaAudit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The model gateway (ADR-0056): every chat call of the qa module goes through {@link #route}. It reads the admin's
 * routes, connections, prices and budgets (cached for {@code verso.qa.gateway.cache-ttl}; an instance sees its own
 * changes at once), builds the provider clients through {@link ChatModelFactory} (cached per connection revision and
 * model), and records every call's tokens and cost, by connection, model, task and team, never by account.
 *
 * <p>Without any route the built-in model of config/verso.yml answers, exactly as before the gateway. The database
 * being unreachable never stops an answer: the last routes read are kept, and with none the built-in model answers.
 *
 * <p>Budgets block only models that cost money (a price row); the built-in model and unpriced connections are never
 * blocked. The month sums are read per call when a budget covers the call; several instances may overshoot by the
 * calls in flight (accepted: the limit is a spending guard, not an invoice).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ModelGateway implements ModelRouter, RoutedChatModel.Hooks {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();
    public static final String BUILT_IN = "built-in";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** What the gateway read from the database, at one moment. */
    record Snapshot(Map<ModelTask, List<ModelRouteEntry>> routes, Map<UUID, ModelConnection> connections,
                    Map<String, ModelPrice> prices, Map<String, BigDecimal[]> budgets, boolean userModelChoice,
                    Instant loadedAt) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), false, Instant.EPOCH);
        }
    }

    private final ModelConnectionRepository connections;
    private final ModelGatewayRepository repository;
    private final ChatModel builtIn;
    private final VersoAiProperties ai;
    private final QaGatewayProperties properties;
    private final UsageMetricsProperties zones;
    private final EnvelopeCipher cipher;
    private final KeyEncryptionKey kek;
    private final ChatModelFactory factory;
    private final ChatModelFactory testFactory;
    private final QaAudit audit;
    private final CallerGroups groups;
    private final Clock clock;
    private final MeterRegistry registry;
    private final ModelRequestFilter filter = new ModelRequestFilter.PassThrough();
    private final Map<String, ChatModel> clients = new ConcurrentHashMap<>();
    private final Counter recordFailed;
    private volatile Snapshot snapshot = Snapshot.empty();
    private final java.util.concurrent.locks.ReentrantLock reload = new java.util.concurrent.locks.ReentrantLock();

    public ModelGateway(ModelConnectionRepository connections, ModelGatewayRepository repository, ChatModel builtIn,
                        VersoAiProperties ai, QaProperties qa, QaGatewayProperties properties,
                        UsageMetricsProperties zones, KeyEncryptionKey kek, QaAudit audit, CallerGroups groups,
                        Clock clock, MeterRegistry registry, ObjectProvider<RestClient.Builder> restClients,
                        ObjectProvider<WebClient.Builder> webClients) {
        this.connections = connections;
        this.repository = repository;
        this.builtIn = builtIn;
        this.ai = ai;
        this.properties = properties;
        this.zones = zones;
        this.kek = kek;
        this.cipher = new EnvelopeCipher(kek);
        this.audit = audit;
        this.groups = groups;
        this.clock = clock;
        this.registry = registry;
        Duration bound = ai.mode() == AiMode.CLOUD ? qa.cloudChatTimeout() : qa.localChatTimeout();
        this.factory = new ChatModelFactory(ai.mode(), bound, restClients::getObject, webClients::getObject);
        this.testFactory = new ChatModelFactory(ai.mode(), properties.checkTimeout(), restClients::getObject,
                webClients::getObject);
        this.recordFailed = Counter.builder("verso.model.gateway.usage.not.stored")
                .description("Model calls whose usage row could not be stored").register(registry);
    }

    // ---------------------------------------------------------------------------------------------------- routing

    @Override
    public ChatModel route(ModelTask task, String choice) {
        return route(task, choice, null);
    }

    /** {@link #route(ModelTask, String)} with the skill library's routing word, kept on the usage row (ADR-0057). */
    public ChatModel route(ModelTask task, String choice, String detail) {
        if (task == ModelTask.EMBEDDING) {
            throw new IllegalArgumentException("Embeddings are not routed: they stay on the local model (llm-rules 1.2)");
        }
        Snapshot s = snapshot();
        List<RoutedChatModel.Candidate> candidates = candidates(s, task);
        if (choice != null) {
            candidates.stream().filter(c -> choiceId(c).equals(choice)).findFirst().ifPresent(chosen -> {
                candidates.remove(chosen);
                candidates.addFirst(chosen);
            });
        }
        List<String> callerGroups = callerGroups();
        return new RoutedChatModel(this, task, detail, candidates, team(s, callerGroups), accountName());
    }

    /** The route's usable models: enabled, allowed in this mode; the built-in model when none is left. */
    List<RoutedChatModel.Candidate> candidates(Snapshot s, ModelTask task) {
        List<RoutedChatModel.Candidate> list = new ArrayList<>();
        for (ModelRouteEntry entry : s.routes().getOrDefault(task, List.of())) {
            if (entry.connectionId() == null) {
                list.add(new RoutedChatModel.Candidate(null, ai.chatModel()));
                continue;
            }
            ModelConnection c = s.connections().get(entry.connectionId());
            if (c != null && c.enabled() && allowedInMode(c)) list.add(new RoutedChatModel.Candidate(c, entry.model()));
        }
        if (list.isEmpty()) list.add(new RoutedChatModel.Candidate(null, ai.chatModel()));
        return list;
    }

    /** The model a task would try first now (for a run record's model id); the built-in model when nothing is routed. */
    public String firstModel(ModelTask task) {
        return candidates(snapshot(), task).getFirst().model();
    }

    /** Local mode: never a cloud connection, whatever the database says (the third guard, llm-rules 1.1). */
    boolean allowedInMode(ModelConnection c) {
        return ai.mode() == AiMode.CLOUD || (c.type().selfHostable() && !c.external());
    }

    /** GET /v1/models: the answer route's models, when the admin allows the choice. */
    public ModelChoicesResponse choices() {
        Snapshot s = snapshot();
        if (!s.userModelChoice()) return new ModelChoicesResponse(false, List.of());
        return new ModelChoicesResponse(true, candidates(s, ModelTask.ANSWER).stream()
                .map(c -> new ModelChoicesResponse.Choice(choiceId(c),
                        (c.connection() == null ? "Yerleşik model" : c.connection().name()) + " · " + c.model(),
                        c.external()))
                .distinct().toList());
    }

    /** A user's choice must be one of {@link #choices()}; null passes. */
    @Override
    public String validChoice(String choice) {
        if (choice == null) return null;
        boolean offered = choices().choices().stream().anyMatch(c -> c.id().equals(choice));
        if (!offered) throw new QaServiceException(QaErrorCode.MODEL_CHOICE_INVALID, "NOT_OFFERED");
        return choice;
    }

    static String choiceId(RoutedChatModel.Candidate c) {
        return c.connection() == null ? BUILT_IN : c.connection().id() + "/" + c.model();
    }

    /**
     * The team a call is counted for: among the caller's token groups (ADR-0028), the first (sorted) with a team
     * budget, else the first; null without groups. One team per call, so team sums never double count.
     */
    static String team(Snapshot s, List<String> callerGroups) {
        List<String> sorted = callerGroups.stream().filter(Objects::nonNull).sorted().toList();
        return sorted.stream().filter(g -> s.budgets().containsKey(budgetKey("TEAM", g))).findFirst()
                .orElse(sorted.isEmpty() ? null : sorted.getFirst());
    }

    private List<String> callerGroups() {
        try {
            return groups.current();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String accountName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    // ---------------------------------------------------------------------------------------------------- hooks

    @Override
    public ChatModel client(RoutedChatModel.Candidate candidate) {
        if (candidate.connection() == null) return builtIn;
        ModelConnection c = candidate.connection();
        return clients.computeIfAbsent(c.id() + "@" + c.revision() + "/" + candidate.model(),
                key -> factory.build(c, candidate.model(), openKey(c), ChatModelFactory.ANSWER_MAX_TOKENS));
    }

    @Override
    public boolean overBudget(RoutedChatModel.Candidate candidate, String team) {
        if (candidate.connection() == null) return false;
        Snapshot s = snapshot;
        ModelPrice price = s.prices().get(priceKey(candidate.connection().id(), candidate.model()));
        if (price == null || (price.inputPerMillion().signum() == 0 && price.outputPerMillion().signum() == 0)) {
            return false;
        }
        String month = month();
        try {
            BigDecimal[] connection = s.budgets().get(budgetKey("CONNECTION", candidate.connection().id().toString()));
            if (connection != null
                    && repository.spentByConnection(month, candidate.connection().id()).compareTo(connection[0]) >= 0) {
                return true;
            }
            BigDecimal[] teamBudget = team == null ? null : s.budgets().get(budgetKey("TEAM", team));
            return teamBudget != null && repository.spentByTeam(month, team).compareTo(teamBudget[0]) >= 0;
        } catch (RuntimeException e) {
            // Cannot read the sums: the call goes ahead (availability), the next one checks again.
            log.warn("Model budget not checked: reason=DATABASE exceptionType={}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public ModelRequestFilter filter() {
        return filter;
    }

    @Override
    public void record(RoutedChatModel.Candidate candidate, ModelTask task, String detail, String team, String account,
                       String outcome, Usage usage, List<ModelRequestFilter.EgressCategory> sent) {
        String month = month();
        UUID connectionId = candidate.connection() == null ? null : candidate.connection().id();
        int prompt = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completion = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        ModelPrice price = connectionId == null ? null : snapshot.prices().get(priceKey(connectionId, candidate.model()));
        BigDecimal cost = cost(price, prompt, completion);
        String locality = candidate.external() ? "external" : "local";
        Counter.builder("verso.model.gateway.calls").tag("task", task.name()).tag("outcome", outcome)
                .tag("locality", locality).register(registry).increment();
        Counter.builder("verso.model.gateway.tokens").tag("task", task.name()).tag("type", "prompt")
                .register(registry).increment(prompt);
        Counter.builder("verso.model.gateway.tokens").tag("task", task.name()).tag("type", "completion")
                .register(registry).increment(completion);
        try {
            repository.insertUsage(new ModelGatewayRepository.Usage(month, connectionId, candidate.model(),
                    task.name(), detail, team, candidate.external(), outcome, prompt, completion, cost,
                    sent.stream().map(Enum::name).toList(), candidate.external() ? filter.policy() : "NONE"));
            if (team != null && account != null && kek.available()) {
                repository.reach(month, team, reachDigest(month, team, account));
            }
            if (cost.signum() > 0) notifyLimits(connectionId, team, month);
        } catch (RuntimeException e) {
            recordFailed.increment();
            log.warn("Model usage not stored: code=USAGE_WRITE_FAILED task={} exceptionType={}", task,
                    e.getClass().getSimpleName());
        }
    }

    static BigDecimal cost(ModelPrice price, int prompt, int completion) {
        if (price == null) return BigDecimal.ZERO;
        return price.inputPerMillion().multiply(BigDecimal.valueOf(prompt))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(completion)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    /** One audit event per scope, level and month (a conditional update elects the reporting instance). */
    private void notifyLimits(UUID connectionId, String team, String month) {
        Snapshot s = snapshot;
        if (connectionId != null) {
            BigDecimal[] limits = s.budgets().get(budgetKey("CONNECTION", connectionId.toString()));
            if (limits != null) {
                notifyLimit("CONNECTION", connectionId.toString(), connectionId, limits,
                        repository.spentByConnection(month, connectionId), month);
            }
        }
        if (team != null) {
            BigDecimal[] limits = s.budgets().get(budgetKey("TEAM", team));
            if (limits != null) notifyLimit("TEAM", team, null, limits, repository.spentByTeam(month, team), month);
        }
    }

    private void notifyLimit(String scope, String key, UUID target, BigDecimal[] limits, BigDecimal spent,
                             String month) {
        String level = spent.compareTo(limits[0]) >= 0 ? "HARD"
                : limits[1] != null && spent.compareTo(limits[1]) >= 0 ? "SOFT" : null;
        if (level == null || !repository.markNotified(scope, key, level, month)) return;
        Counter.builder("verso.model.gateway.budget.limit").tag("scope", scope).tag("level", level)
                .register(registry).increment();
        // The team's name is a group name; it stays out of the log, the scope and the level are enough.
        log.warn("Model budget limit reached: scope={} level={}", scope, level);
        audit.modelBudgetLimitReached(target, scope, level);
    }

    private byte[] reachDigest(String month, String team, String account) {
        return LocalKeyEncryptionKey.hmac(kek.derive("usage-reach"),
                (month + "\n" + team + "\n" + account).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------------------------------------------- test call

    /** The result of a validation call, with the time it took. */
    public record TestResult(String status, long latencyMs) {
        public boolean ok() {
            return "OK".equals(status);
        }
    }

    /**
     * A tiny completion with the connection's first model (ADR-0056: validated on save, rotation and on request),
     * bounded by {@code check-timeout}, outside any transaction. {@code key} is the key to test (a new or rotated one)
     * or null to use the stored key. The status is a fixed word; the provider's text is never kept.
     */
    public TestResult test(ModelConnection connection, String key) {
        long started = System.nanoTime();
        String status;
        Usage usage = null;
        try {
            String secret = key != null ? key : openKey(connection);
            ChatModel model = testFactory.build(connection, connection.models().getFirst(), secret,
                    ChatModelFactory.TEST_MAX_TOKENS);
            Prompt prompt = new Prompt(List.of(new SystemMessage("You are a connection test."),
                    new UserMessage("Reply with the single word OK.")));
            Future<ChatResponse> call = VIRTUAL.submit(() -> model.call(prompt));
            try {
                ChatResponse response = call.get(properties.checkTimeout().toMillis(), TimeUnit.MILLISECONDS);
                usage = RoutedChatModel.usageOf(response);
                status = "OK";
            } catch (TimeoutException e) {
                call.cancel(true);
                status = "UNREACHABLE";
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                status = FailureClassifier.status(FailureClassifier.classify(cause), cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "FAILED";
        } catch (RuntimeException e) {
            // A client that cannot be built (malformed service account, unknown region): the type only.
            log.warn("Model connection test failed: reason=CLIENT exceptionType={}", e.getClass().getSimpleName());
            status = "FAILED";
        }
        long latency = (System.nanoTime() - started) / 1_000_000;
        try {
            repository.insertUsage(new ModelGatewayRepository.Usage(month(), connection.id(),
                    connection.models().getFirst(), "TEST", null, null, connection.external(), "OK".equals(status) ? "OK"
                    : "FAILED", usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                    usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(),
                    BigDecimal.ZERO, connection.external() ? List.of("TEST_PROMPT") : List.of(),
                    connection.external() ? filter.policy() : "NONE"));
        } catch (RuntimeException e) {
            recordFailed.increment();
        }
        log.info("Model connection tested: connectionId={} type={} status={} latencyMs={}", connection.id(),
                connection.type(), status, latency);
        return new TestResult(status, latency);
    }

    // ---------------------------------------------------------------------------------------------------- state

    private String openKey(ModelConnection c) {
        if (c.key() == null) return null;
        if (!cipher.available()) throw new IllegalStateException("The model gateway key store is not set up");
        return cipher.open(c.key(), c.id(), c.keyVersion());
    }

    public EnvelopeCipher cipher() {
        return cipher;
    }

    String month() {
        return YearMonth.now(clock.withZone(zones.zone())).toString();
    }

    /** The current snapshot, re-read when older than the TTL; the last good one when the database fails. */
    Snapshot snapshot() {
        Snapshot current = snapshot;
        if (Duration.between(current.loadedAt(), clock.instant()).compareTo(properties.cacheTtl()) < 0) return current;
        // One thread reloads; the others keep answering with the last snapshot instead of queueing behind a slow
        // database (resilience review R1). Only before the first load do they wait (there is nothing to serve yet).
        boolean neverLoaded = current.loadedAt().equals(Instant.EPOCH) && current.connections().isEmpty()
                && current.routes().isEmpty();
        if (neverLoaded) {
            reload.lock();
        } else if (!reload.tryLock()) {
            return current;
        }
        try {
            current = snapshot;
            if (Duration.between(current.loadedAt(), clock.instant()).compareTo(properties.cacheTtl()) < 0) {
                return current;
            }
            try {
                snapshot = load();
                dropStaleClients(snapshot);
            } catch (RuntimeException e) {
                log.warn("Model gateway settings not read, the last ones stay: reason=DATABASE exceptionType={}",
                        e.getClass().getSimpleName());
                // Try again after the TTL, not on every call.
                snapshot = new Snapshot(current.routes(), current.connections(), current.prices(), current.budgets(),
                        current.userModelChoice(), clock.instant());
            }
            return snapshot;
        } finally {
            reload.unlock();
        }
    }

    /** After an admin change on this instance: the next call reads the database. */
    public void invalidate() {
        Snapshot current = snapshot;
        snapshot = new Snapshot(current.routes(), current.connections(), current.prices(), current.budgets(),
                current.userModelChoice(), Instant.EPOCH);
    }

    private Snapshot load() {
        Map<UUID, ModelConnection> byId = connections.list().stream()
                .collect(Collectors.toMap(ModelConnection::id, c -> c));
        Map<String, ModelPrice> prices = new HashMap<>();
        repository.prices().forEach(p -> prices.put(priceKey(p.connectionId(), p.model()), p));
        Map<String, BigDecimal[]> budgets = new HashMap<>();
        repository.budgets().forEach(b -> budgets.put(budgetKey(b.scope(), b.scopeKey()),
                new BigDecimal[]{b.hardLimit(), b.softLimit()}));
        return new Snapshot(repository.routes(), Map.copyOf(byId), Map.copyOf(prices), Map.copyOf(budgets),
                repository.userModelChoice(), clock.instant());
    }

    private void dropStaleClients(Snapshot s) {
        clients.keySet().removeIf(key -> {
            int at = key.indexOf('@');
            UUID id = UUID.fromString(key.substring(0, at));
            long revision = Long.parseLong(key.substring(at + 1, key.indexOf('/', at)));
            ModelConnection c = s.connections().get(id);
            return c == null || c.revision() != revision || !c.enabled();
        });
    }

    static String priceKey(UUID connectionId, String model) {
        return connectionId + "/" + model;
    }

    static String budgetKey(String scope, String key) {
        return scope + "/" + key;
    }

    /** For the startup check: the enabled connections local mode refuses. */
    List<ModelConnection> forbiddenInLocalMode() {
        return connections.list().stream().filter(ModelConnection::enabled)
                .filter(c -> !c.type().selfHostable() || c.external())
                .sorted(Comparator.comparing(ModelConnection::name)).toList();
    }
}
