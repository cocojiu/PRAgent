package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminApiKeyAccessPolicyTest {

    @Test
    void protectsIntentionalAdminApiKeyScope() {
        assertThat(AdminApiKeyAccessPolicy.protectedEndpoints())
            .extracting(endpoint -> endpoint.method() + " " + endpoint.pathPattern())
            .containsExactly(
                "* /api/v1/config",
                "* /api/v1/config/**",
                "POST /api/v1/reviews/manual",
                "POST /api/v1/reviews/{id}/retry",
                "POST /api/v1/reviews/{id}/human-review",
                "POST /api/v1/reviews/{id}/github-comments",
                "POST /api/v1/reviews/{id}/findings/{findingId}/feedback",
                "POST /api/v1/message-queue/tasks/{taskId}/requeue",
                "POST /api/v1/notification-events/{id}/retry"
            );
    }

    @Test
    void matchesProtectedTemplatePathsOnlyWhenNumericIdsArePresent() {
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/42/retry")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/42/findings/7/feedback")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/notification-events/9/retry")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/reviews/current/retry")).isFalse();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/reviews/42/retry")).isFalse();
    }

    @Test
    void protectsConfigurationTreeForReadAndWriteAccess() {
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/config/review-policy")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("PUT", "/api/v1/config/system-settings")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("POST", "/api/v1/config/data-retention/cleanup")).isTrue();
        assertThat(AdminApiKeyAccessPolicy.requiresAdminKey("GET", "/api/v1/reviews/42")).isFalse();
    }
}
