package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.testsupport.ControllerEndpointCatalog;
import com.repoguard.agent.testsupport.ControllerEndpointCatalog.Endpoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthTokenAccessPolicyTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.repoguard.agent.controller";

    @Test
    void keepsIntentionalPublicApiScope() {
        assertThat(AuthTokenAccessPolicy.publicEndpoints())
            .extracting(endpoint -> endpoint.method() + " " + endpoint.pathPattern())
            .containsExactly(
                "POST /api/v1/auth/register",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/refresh",
                "POST /api/v1/auth/refresh-token/reset",
                "POST /api/v1/auth/logout",
                "POST /api/v1/github/webhooks"
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
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1/auth/login")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("DELETE", "/api/v1/auth/logout")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1/github/webhooks")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/auth/login/extra")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/github/webhooks/extra")).isTrue();
    }

    @Test
    void policyAndAnonymousControllerMappingsStayBidirectionallyConsistent() throws ClassNotFoundException {
        List<String> anonymousMappings = new ArrayList<>();
        for (Class<?> controller : ControllerEndpointCatalog.discoverControllers(CONTROLLER_BASE_PACKAGE)) {
            for (Endpoint endpoint : ControllerEndpointCatalog.endpoints(controller)) {
                if (allowsAnonymous(endpoint)) {
                    anonymousMappings.add(mappedEndpoint(endpoint));
                }
            }
        }

        assertThat(AuthTokenAccessPolicy.publicEndpoints())
            .extracting(endpoint -> endpoint.method() + " " + endpoint.pathPattern())
            .as("Every @AllowAnonymous mapping and public token policy entry must have an exact counterpart")
            .containsExactlyInAnyOrderElementsOf(anonymousMappings);
    }

    @Test
    void ignoresNonApiPaths() {
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/actuator/health")).isFalse();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/")).isFalse();
    }

    @Test
    void coversHandlerEquivalentPathVariants() {
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1;x=1/users")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1/reviews;x=1")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/%61pi/v1/reviews")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1/%72eviews")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/api/v1//reviews")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("GET", "/API/v1/reviews")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/auth/login/")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/AUTH/login")).isTrue();
        assertThat(AuthTokenAccessPolicy.requiresAuth("POST", "/api/v1/auth/login;x=1")).isFalse();
    }

    private boolean allowsAnonymous(Endpoint endpoint) {
        return endpoint.method().isAnnotationPresent(AllowAnonymous.class)
            || endpoint.controller().isAnnotationPresent(AllowAnonymous.class);
    }

    private String mappedEndpoint(Endpoint endpoint) {
        return endpoint.httpMethod() + " " + endpoint.path();
    }
}
