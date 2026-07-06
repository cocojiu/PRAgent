package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthTokenAccessPolicyTest {

    @Test
    void keepsIntentionalPublicApiScope() {
        assertThat(AuthTokenAccessPolicy.publicEndpoints())
            .extracting(endpoint -> endpoint.method() + " " + endpoint.pathPattern())
            .containsExactly(
                "* /api/v1/auth/register",
                "* /api/v1/auth/login",
                "* /api/v1/auth/refresh",
                "* /api/v1/auth/refresh-token/reset",
                "* /api/v1/auth/logout",
                "* /api/v1/github/webhooks"
            );
    }

    @Test
    void protectsApiEndpointsExceptExplicitPublicEntrypoints() {
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1/auth/me")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1/reviews")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/config/review-policy")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/observability/frontend/performance")).isTrue();
    }

    @Test
    void allowsOnlyExplicitPublicEntrypointsWithoutAuth() {
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/auth/login")).isFalse();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/auth/refresh")).isFalse();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/auth/logout")).isFalse();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/github/webhooks")).isFalse();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/auth/login/extra")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/github/webhooks/extra")).isTrue();
    }

    @Test
    void ignoresNonApiPaths() {
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/actuator/health")).isFalse();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/")).isFalse();
    }
}
