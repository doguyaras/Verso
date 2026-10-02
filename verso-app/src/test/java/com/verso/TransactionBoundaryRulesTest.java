package com.verso;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * No remote call while a transaction is open (reference 4.3, 16; llm-rules 5.1; ADR-0007 #9). A model or HTTP call
 * takes seconds: inside a transaction it holds a pooled connection, row locks and advisory locks for that long.
 *
 * <p>Remote means: Spring AI types (ChatModel, ChatClient, EmbeddingModel, and VectorStore, whose add() embeds
 * through the model), RestClient, RestTemplate, WebClient, java.net.http.HttpClient, and every Verso class named
 * {@code *Client}. Transactional means: {@code @Transactional} on the method, or on the class for its public methods,
 * unless the propagation suspends or forbids a transaction (NOT_SUPPORTED, NEVER).
 *
 * <p>Limit (reference 16): ArchUnit sees direct calls only; a call through a helper bean is a review item.
 */
class TransactionBoundaryRulesTest {

    static final String ROOT = "com.verso";

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
                .hasMessageContaining("restCallInsideTransaction")
                .hasMessageContaining("TransactionalService.answer")
                .hasMessageNotContaining("restCallWithTransactionSuspended")
                .hasMessageNotContaining("restCallWithoutTransaction");
    }

    @Test
    void remoteTypes_whenNamed_includeSpringAiHttpClientsAndVersoClients() {
        assertThat(isRemote("org.springframework.ai.chat.model.ChatModel")).isTrue();
        assertThat(isRemote("org.springframework.ai.chat.client.ChatClient")).isTrue();
        assertThat(isRemote("org.springframework.ai.embedding.EmbeddingModel")).isTrue();
        assertThat(isRemote("org.springframework.ai.vectorstore.VectorStore")).isTrue();
        assertThat(isRemote("org.springframework.web.client.RestClient")).isTrue();
        assertThat(isRemote("java.net.http.HttpClient")).isTrue();
        assertThat(isRemote("com.verso.qa.client.OllamaClient")).isTrue();
        assertThat(isRemote("org.springframework.jdbc.core.JdbcTemplate")).isFalse();
        assertThat(isRemote("com.verso.document.ClientName")).isFalse();
    }

    static boolean isRemote(String typeName) {
        if (typeName.startsWith(SPRING_AI) || REMOTE_TYPES.contains(typeName)) return true;
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        return typeName.startsWith(ROOT + ".") && simple.endsWith("Client");
    }

    static DescribedPredicate<JavaMethod> areTransactional() {
        return DescribedPredicate.describe("are transactional", method -> {
            Optional<Transactional> own = method.tryGetAnnotationOfType(Transactional.class);
            if (own.isPresent()) return opensTransaction(own.get());
            Optional<Transactional> onClass = method.getOwner().tryGetAnnotationOfType(Transactional.class);
            return onClass.isPresent() && method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)
                    && opensTransaction(onClass.get());
        });
    }

    private static boolean opensTransaction(Transactional transactional) {
        return transactional.propagation() != Propagation.NOT_SUPPORTED && transactional.propagation() != Propagation.NEVER;
    }

    static ArchCondition<JavaMethod> callRemoteTypes() {
        return new ArchCondition<>("call remote types (Spring AI, HTTP clients, *Client)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    String target = call.getTargetOwner().getName();
                    if (isRemote(target)) {
                        events.add(SimpleConditionEvent.satisfied(method,
                                method.getFullName() + " calls " + target + " in " + call.getSourceCodeLocation()));
                    }
                }
            }
        };
    }
}
