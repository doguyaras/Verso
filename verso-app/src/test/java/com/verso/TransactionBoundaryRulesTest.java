package com.verso;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * No remote call while a transaction is open (reference 4.3, 16; llm-rules 5.1; ADR-0007 #9). A model or HTTP call
 * takes seconds: inside a transaction it holds a pooled connection, row locks and advisory locks for that long.
 *
 * <p><b>Remote</b>: RestClient, RestTemplate, WebClient and java.net.http.HttpClient with their nested types
 * (RestClient$RequestHeadersSpec.retrieve()), every Verso {@code *Client}, and the network types of Spring AI: names
 * ending in Model or Client (ChatModel, EmbeddingModel, OllamaChatModel, ChatClient) and VectorStore (add() embeds).
 * Local Spring AI types (Document, text splitters, prompts) are not remote (phase 2 review A3).
 *
 * <p><b>Transactional</b> (phase 2 reviews A2, C3; Spring 7 also proxies non-public methods): Spring's
 * {@code @Transactional} or {@code jakarta.transaction.Transactional}, directly or as a meta-annotation, on the
 * method, on an interface method it implements, or on its class or a superclass; private and static methods are
 * never proxied. Propagation NOT_SUPPORTED and NEVER (TxType likewise) open no transaction. Calls are followed
 * through private and other same-class helper methods.
 *
 * <p>Still a review item (reference 16): calls through another bean, and TransactionTemplate callbacks.
 */
class TransactionBoundaryRulesTest {

    static final String ROOT = "com.verso";
    static final String SPRING_TX = "org.springframework.transaction.annotation.Transactional";
    static final String JAKARTA_TX = "jakarta.transaction.Transactional";
    static final List<String> REMOTE_TYPES = List.of(
            "org.springframework.web.client.RestClient",
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.reactive.function.client.WebClient",
            "java.net.http.HttpClient");
    static final String SPRING_AI = "org.springframework.ai.";

    /**
     * No transactional method calls a remote type. Empty subjects are allowed in production (no @Transactional method
     * exists before phase 4); the fixture test below proves the rule is not a silent green.
     */
    static final ArchRule NO_REMOTE_CALL_IN_TRANSACTION = noMethods()
            .that(areTransactional())
            .should(callRemoteTypes())
            .because("a model or HTTP call inside a transaction holds a connection and locks for seconds (reference 4.3)");

    @Test
    void transactionalMethods_whenProductionCodeImported_makeNoRemoteCalls() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        NO_REMOTE_CALL_IN_TRANSACTION.allowEmptyShould(true).check(production);
    }

    @Test
    void rule_whenFixtureCallsRemoteTypesInTransactions_reportsExactlyThoseMethods() {
        JavaClasses fixture = new ClassFileImporter().importPackages(ROOT + ".archfixture.tx");
        assertThatThrownBy(() -> NO_REMOTE_CALL_IN_TRANSACTION.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TransactionalRemoteCalls.restCallInsideTransaction")
                .hasMessageContaining("TransactionalService.answer")
                .hasMessageContaining("HiddenTransactions.composedAnnotation")
                .hasMessageContaining("HiddenTransactions.packagePrivateUnderClassLevel")
                .hasMessageContaining("HiddenTransactions.viaPrivateHelper")
                .hasMessageContaining("RestClient$RequestHeadersUriSpec")
                .hasMessageContaining("TransactionalPortImpl.fetch")
                .hasMessageContaining("InheritedTransaction.call")
                .hasMessageContaining("TransactionalLambdaRemoteCall.restCallInLambdaInsideTransaction")
                .hasMessageNotContaining("restCallWithTransactionSuspended")
                .hasMessageNotContaining("restCallWithoutTransaction")
                .hasMessageNotContaining("staticCall");
    }

    @Test
    void remoteTypes_whenNamed_includeNetworkTypesOnly() {
        assertThat(isRemote("org.springframework.ai.chat.model.ChatModel")).isTrue();
        assertThat(isRemote("org.springframework.ai.chat.model.StreamingChatModel")).isTrue();
        assertThat(isRemote("org.springframework.ai.chat.client.ChatClient")).isTrue();
        assertThat(isRemote("org.springframework.ai.chat.client.ChatClient$CallResponseSpec")).isTrue();
        assertThat(isRemote("org.springframework.ai.embedding.EmbeddingModel")).isTrue();
        assertThat(isRemote("org.springframework.ai.ollama.OllamaChatModel")).isTrue();
        assertThat(isRemote("org.springframework.ai.vectorstore.VectorStore")).isTrue();
        assertThat(isRemote("org.springframework.ai.vectorstore.pgvector.PgVectorStore")).isTrue();
        assertThat(isRemote("org.springframework.web.client.RestClient")).isTrue();
        assertThat(isRemote("org.springframework.web.client.RestClient$RequestHeadersSpec")).isTrue();
        assertThat(isRemote("java.net.http.HttpClient")).isTrue();
        assertThat(isRemote("org.springframework.web.client.RestTemplate")).isTrue();
        assertThat(isRemote("org.springframework.web.reactive.function.client.WebClient")).isTrue();
        assertThat(isRemote("com.verso.qa.client.OllamaClient")).isTrue();
        assertThat(isRemote("org.springframework.ai.document.Document")).isFalse();
        assertThat(isRemote("org.springframework.ai.transformer.splitter.TokenTextSplitter")).isFalse();
        assertThat(isRemote("org.springframework.ai.chat.prompt.Prompt")).isFalse();
        assertThat(isRemote("org.springframework.jdbc.core.JdbcTemplate")).isFalse();
        assertThat(isRemote("org.springframework.web.client.RestClientException")).isFalse();
        assertThat(isRemote("com.verso.document.ClientName")).isFalse();
    }

    static boolean isRemote(String typeName) {
        for (String remote : REMOTE_TYPES) {
            if (typeName.equals(remote) || typeName.startsWith(remote + "$")) return true;
        }
        String outer = typeName.contains("$") ? typeName.substring(0, typeName.indexOf('$')) : typeName;
        String simple = outer.substring(outer.lastIndexOf('.') + 1);
        if (outer.startsWith(SPRING_AI)) {
            return simple.endsWith("Model") || simple.endsWith("Client") || simple.endsWith("VectorStore");
        }
        return outer.startsWith(ROOT + ".") && simple.endsWith("Client");
    }

    static DescribedPredicate<JavaMethod> areTransactional() {
        return DescribedPredicate.describe("are transactional", TransactionBoundaryRulesTest::isTransactional);
    }

    static boolean isTransactional(JavaMethod method) {
        Set<JavaModifier> modifiers = method.getModifiers();
        if (modifiers.contains(JavaModifier.PRIVATE) || modifiers.contains(JavaModifier.STATIC)) return false;
        Optional<Boolean> own = opensTransaction(method);
        if (own.isPresent()) return own.get();
        for (JavaClass type : method.getOwner().getAllRawInterfaces()) {
            for (JavaMethod declared : type.getMethods()) {
                if (declared.getName().equals(method.getName())
                        && declared.getRawParameterTypes().equals(method.getRawParameterTypes())) {
                    Optional<Boolean> viaInterface = opensTransaction(declared);
                    if (viaInterface.isPresent()) return viaInterface.get();
                }
            }
        }
        for (JavaClass type = method.getOwner(); type != null; type = type.getRawSuperclass().orElse(null)) {
            Optional<Boolean> onClass = opensTransaction(type);
            if (onClass.isPresent()) return onClass.get();
        }
        for (JavaClass type : method.getOwner().getAllRawInterfaces()) {
            Optional<Boolean> onInterface = opensTransaction(type);
            if (onInterface.isPresent()) return onInterface.get();
        }
        return false;
    }

    /** Empty: no transactional annotation here; otherwise whether its propagation opens a transaction. */
    private static Optional<Boolean> opensTransaction(HasAnnotations<?> element) {
        return findTransactional(element.getAnnotations(), 0).map(annotation -> {
            Object propagation = annotation.get(annotation.getRawType().getName().equals(SPRING_TX) ? "propagation"
                    : "value").orElse(null);
            String name = propagation instanceof JavaEnumConstant constant ? constant.name() : "REQUIRED";
            return !name.equals("NOT_SUPPORTED") && !name.equals("NEVER");
        });
    }

    private static Optional<JavaAnnotation<?>> findTransactional(Set<? extends JavaAnnotation<?>> annotations, int depth) {
        if (depth > 3) return Optional.empty();
        for (JavaAnnotation<?> annotation : annotations) {
            String type = annotation.getRawType().getName();
            if (type.equals(SPRING_TX) || type.equals(JAKARTA_TX)) return Optional.of(annotation);
            if (type.startsWith("java.lang.annotation.")) continue;
            Optional<JavaAnnotation<?>> meta = findTransactional(annotation.getRawType().getAnnotations(), depth + 1);
            if (meta.isPresent()) return meta;
        }
        return Optional.empty();
    }

    static ArchCondition<JavaMethod> callRemoteTypes() {
        return new ArchCondition<>("call remote types (Spring AI network types, HTTP clients, *Client)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Set<JavaMethod> visited = new HashSet<>();
                Deque<JavaMethod> pending = new ArrayDeque<>(List.of(method));
                while (!pending.isEmpty()) {
                    JavaMethod current = pending.pop();
                    if (!visited.add(current)) continue;
                    for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                        String target = call.getTargetOwner().getName();
                        if (isRemote(target)) {
                            events.add(SimpleConditionEvent.satisfied(method, method.getFullName() + " calls " + target
                                    + (current.equals(method) ? "" : " via " + current.getName()) + " in "
                                    + call.getSourceCodeLocation()));
                        } else if (call.getTargetOwner().equals(method.getOwner())) {
                            call.getTarget().resolveMember().ifPresent(pending::push);
                        }
                    }
                }
            }
        };
    }
}
