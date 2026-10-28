package com.verso.work.config;

import com.verso.work.model.ChatModelSkillModel;
import com.verso.work.api.SkillModel;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The skill library's model port (ADR-0057).
 *
 * <p><b>Model gateway integration point.</b> {@link SkillModel} (work-api) is the only way the runtime reaches a
 * model. The model gateway (ADR-0056, qa module) declares its own {@code @Primary} {@code SkillModel} bean, which routes
 * by {@link SkillModel.Call#task()} with its fallbacks, budgets and usage record; this adapter on the installation's
 * {@code ChatModel} only serves an assembly without the gateway. {@code @ConditionalOnMissingBean} on a scanned
 * configuration depends on the order the beans are registered in, so the gateway's bean is also {@code @Primary}:
 * whichever order, the runtime gets the gateway's.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkConfiguration {

    @Bean
    @ConditionalOnMissingBean(SkillModel.class)
    SkillModel skillModel(ChatModel chat, WorkProperties properties, Clock clock) {
        return new ChatModelSkillModel(chat, properties.modelConcurrency(), properties.failureThreshold(),
                properties.openFor(), clock);
    }
}
