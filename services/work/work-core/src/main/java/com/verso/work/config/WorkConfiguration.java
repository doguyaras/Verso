package com.verso.work.config;

import com.verso.work.model.ChatModelSkillModel;
import com.verso.work.model.SkillModel;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The skill library's model port (ADR-0057).
 *
 * <p><b>Model gateway integration point.</b> {@link SkillModel} is the only way the runtime reaches a model. Until the
 * provider-agnostic gateway lands, the adapter below wraps the installation's {@code ChatModel} (whatever provider and
 * AI mode verso-app configured; this module adds no provider, URL or key). The gateway takes over by declaring a
 * {@code SkillModel} bean of its own: this one then steps aside ({@code @ConditionalOnMissingBean}), and the gateway
 * routes by {@link SkillModel.Call#task()} and enforces its budgets in {@link SkillModel#tryAcquire}.
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
