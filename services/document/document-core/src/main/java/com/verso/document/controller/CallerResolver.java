package com.verso.document.controller;

import com.verso.document.api.dto.Reader;
import com.verso.document.service.Caller;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.authority.CallerRoles;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The request's {@link Caller}: the account from {@code @CurrentAccount}, the groups and the admin role from the same
 * validated token's authorities (ADR-0019). Nothing comes from the request path, query or body.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CallerResolver {

    private final CallerGroups groups;
    private final CallerRoles roles;

    public CallerResolver(CallerGroups groups, CallerRoles roles) {
        this.groups = groups;
        this.roles = roles;
    }

    public Caller resolve(AccountId account) {
        return new Caller(account, new Reader(account.value(), groups.current()),
                roles.current().contains(VersoRoles.ADMIN));
    }
}
