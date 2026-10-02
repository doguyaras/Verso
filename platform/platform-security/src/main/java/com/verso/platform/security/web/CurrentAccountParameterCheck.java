package com.verso.platform.security.web;

import java.util.Collection;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Startup check: every {@link CurrentAccount} parameter of every handler method is an {@link AccountId}. A wrongly
 * typed parameter stops the application instead of waiting for the first request (fail fast; reference 6.5).
 */
public final class CurrentAccountParameterCheck implements SmartInitializingSingleton {

    private final ListableBeanFactory beans;

    public CurrentAccountParameterCheck(ListableBeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (RequestMappingHandlerMapping mapping : beans.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            verify(mapping.getHandlerMethods().values());
        }
    }

    static void verify(Collection<HandlerMethod> handlers) {
        for (HandlerMethod handler : handlers) {
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(CurrentAccount.class)) requireAccountId(parameter);
            }
        }
    }

    static void requireAccountId(MethodParameter parameter) {
        if (!AccountId.class.equals(parameter.getParameterType())) {
            throw new IllegalStateException("@CurrentAccount must annotate an AccountId parameter, not "
                    + parameter.getParameterType().getSimpleName() + " (" + parameter.getExecutable() + ")");
        }
    }
}
