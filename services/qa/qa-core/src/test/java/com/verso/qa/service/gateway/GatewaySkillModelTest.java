package com.verso.qa.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.service.ModelBudgetExceededException;
import com.verso.work.api.SkillModel;
import com.verso.work.api.SkillModelException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

/** ADR-0056 × ADR-0057: skill runs go through the gateway's SKILL_RUN route; failures become the port's fixed words. */
class GatewaySkillModelTest {

    private final ModelGateway gateway = mock(ModelGateway.class);
    private final ChatModel routed = mock(ChatModel.class);
    private final GatewaySkillModel model = new GatewaySkillModel(gateway,
            new MockEnvironment().withProperty("verso.work.model-concurrency", "1"));

    private static SkillModel.Call call(String task) {
        return new SkillModel.Call(task, "system", "synthetic user text", Duration.ofSeconds(2));
    }

    @Test
    void complete_routesThroughSkillRun_withTheRoutingWord() {
        when(gateway.route(eq(ModelTask.SKILL_RUN), isNull(), any())).thenReturn(routed);
        when(routed.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("taslak")))));

        assertThat(model.complete(call("skill:toplanti:actions"))).isEqualTo("taslak");
        verify(gateway).route(ModelTask.SKILL_RUN, null, "skill:toplanti:actions");
    }

    @Test
    void complete_whenEveryModelIsOverBudget_failsWithTheBudgetWord_andAnOddWordIsNotStored() {
        when(gateway.route(eq(ModelTask.SKILL_RUN), isNull(), any())).thenReturn(routed);
        when(routed.call(any(Prompt.class))).thenThrow(new ModelBudgetExceededException());

        assertThatThrownBy(() -> model.complete(call("free text with spaces"))).isInstanceOf(SkillModelException.class)
                .extracting(e -> ((SkillModelException) e).reason()).isEqualTo("MODEL_BUDGET_EXCEEDED");
        verify(gateway).route(ModelTask.SKILL_RUN, null, null);
    }

    @Test
    void complete_whenTheProviderFails_isModelUnavailable() {
        when(gateway.route(eq(ModelTask.SKILL_RUN), isNull(), any())).thenReturn(routed);
        when(routed.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider text"));

        assertThatThrownBy(() -> model.complete(call("skill:x:y"))).isInstanceOf(SkillModelException.class)
                .extracting(e -> ((SkillModelException) e).reason()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void tryAcquire_isTheBulkheadOfTheWorkModulesSize() {
        SkillModel.Lease first = model.tryAcquire(Duration.ZERO).orElseThrow();
        assertThat(model.tryAcquire(Duration.ofMillis(10))).isEmpty();
        first.close();
        first.close();
        assertThat(model.tryAcquire(Duration.ZERO)).isPresent();
    }
}
