package com.verso.platform.security.web;

import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentAccount} parameters from the authenticated JWT only. It claims every parameter carrying the
 * annotation, whatever its type: a resolver that only matched {@link AccountId} let Spring bind a
 * {@code @CurrentAccount String account} from the query string, i.e. any caller could act as any account (phase 3
 * security review S1). A wrong type is refused here and, earlier, at startup ({@link CurrentAccountParameterCheck}).
 */
public final class CurrentAccountArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAccount.class);
    }

    @Override
    public AccountId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                     NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        CurrentAccountParameterCheck.requireAccountId(parameter);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            return new AccountId(jwt.getToken().getSubject());
        }
        // Fail closed: a controller asking for the account never runs without one (e.g. a path wrongly permitted).
        throw new ServiceException(CommonErrorCode.UNAUTHENTICATED, "NO_ACCOUNT");
    }
}
