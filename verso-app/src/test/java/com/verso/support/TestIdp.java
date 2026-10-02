package com.verso.support;

import com.verso.platform.security.testing.TestIdentityProvider;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The test identity provider for application contexts, and the Authorization header of a valid token. Only the two
 * deployment inputs are set (OIDC_ISSUER, OIDC_JWK_SET_URI, as compose and prod.env do); audience and type header come
 * from the application's own config/verso.yml, so the tests exercise the production token rules.
 */
public final class TestIdp {

    public static final TestIdentityProvider IDP = TestIdentityProvider.get();

    private TestIdp() {}

    public static String bearer(String account) {
        return "Bearer " + IDP.tokenFor(account);
    }

    public static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of("OIDC_ISSUER=" + TestIdentityProvider.ISSUER, "OIDC_JWK_SET_URI=" + IDP.jwkSetUri())
                    .applyTo(context);
        }
    }
}
