package com.verso.qa.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.QuestionRequest;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.NamedInterface;

/** The contract module's rules (ADR-0007 #24): every package is the named interface "api"; values are records. */
class QaApiContractTest {

    @Test
    void packages_whenCompiled_areAllNamedInterfaceApi() throws Exception {
        Set<String> packages = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:com/verso/qa/api/**/*.class")) {
            String path = resource.getURL().toString();
            String relative = path.substring(path.indexOf("com/verso/qa/api"));
            packages.add(relative.substring(0, relative.lastIndexOf('/')).replace('/', '.'));
        }
        assertThat(packages).contains("com.verso.qa.api", "com.verso.qa.api.dto");
        for (String name : packages) {
            NamedInterface named = Class.forName(name + ".package-info").getAnnotation(NamedInterface.class);
            assertThat(named).as(name).isNotNull();
            assertThat(named.value()).as(name).containsExactly("api");
        }
    }

    @Test
    void types_whenExposed_areRecords() {
        for (Class<?> type : List.of(QuestionRequest.class, AnswerResponse.class, Citation.class,
                com.verso.qa.api.dto.ConversationSummary.class, com.verso.qa.api.dto.ConversationResponse.class,
                com.verso.qa.api.dto.TurnResponse.class, com.verso.qa.api.dto.HistoryCitation.class,
                com.verso.qa.api.dto.TurnFeedback.class, com.verso.qa.api.dto.FeedbackRequest.class,
                com.verso.qa.api.dto.RenameConversationRequest.class, com.verso.qa.api.dto.UsageMetricsResponse.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
        }
    }

    /** ADR-0023: the history fields come last, so the JSON of an answer only grows (additive change). */
    @Test
    void answerResponse_whenStored_keepsTheAnswerAndAddsItsIds() {
        java.util.UUID conversation = java.util.UUID.randomUUID();
        java.util.UUID turn = java.util.UUID.randomUUID();
        AnswerResponse answer = new AnswerResponse("a", true, com.verso.qa.api.dto.AnswerOutcome.ANSWERED, List.of(),
                "local", "m", null, null);
        AnswerResponse stored = answer.stored(conversation, turn);
        assertThat(stored.conversationId()).isEqualTo(conversation);
        assertThat(stored.turnId()).isEqualTo(turn);
        assertThat(stored.answer()).isEqualTo("a");
        var components = AnswerResponse.class.getRecordComponents();
        assertThat(components[components.length - 2].getName()).isEqualTo("conversationId");
        assertThat(components[components.length - 1].getName()).isEqualTo("turnId");
    }

    /** The feedback rules a client can break before the server looks at the turn (ADR-0023). */
    @Test
    void feedbackRequest_whenReasonAndCitationDisagree_isInconsistent() {
        var up = com.verso.qa.api.dto.FeedbackRating.UP;
        var down = com.verso.qa.api.dto.FeedbackRating.DOWN;
        var wrong = com.verso.qa.api.dto.FeedbackReason.WRONG_SOURCE;
        var other = com.verso.qa.api.dto.FeedbackReason.OTHER;
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(up, null, null).isReasonOnlyWithDown()).isTrue();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(up, other, null).isReasonOnlyWithDown()).isFalse();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, wrong, 1).isCitationNumberOnlyWithWrongSource()).isTrue();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, wrong, null).isCitationNumberOnlyWithWrongSource()).isFalse();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, other, 2).isCitationNumberOnlyWithWrongSource()).isFalse();
        assertThat(new com.verso.qa.api.dto.FeedbackRequest(down, null, null).isCitationNumberOnlyWithWrongSource()).isTrue();
    }
}
