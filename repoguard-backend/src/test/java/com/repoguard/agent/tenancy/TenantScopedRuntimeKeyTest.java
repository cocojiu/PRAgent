package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.task.ManualReviewIdempotencyCoordinator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TenantScopedRuntimeKeyTest {

    @AfterEach
    void tenantContextIsCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void sameBusinessKeyDiffersAcrossTenants() {
        TenantScopedKey tenantTwo;
        TenantScopedKey tenantThree;
        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            tenantTwo = TenantScopedKey.current("same-key");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(3L)) {
            tenantThree = TenantScopedKey.current("same-key");
        }

        assertThat(tenantTwo).isNotEqualTo(tenantThree);
        assertThat(tenantTwo.belongsTo(2L)).isTrue();
        assertThat(tenantThree.belongsTo(3L)).isTrue();
    }

    @Test
    void manualReviewInflightOwnerIsTenantScoped() {
        ManualReviewIdempotencyCoordinator coordinator = new ManualReviewIdempotencyCoordinator(
            Mockito.mock(ScheduledExecutorService.class)
        );
        CompletableFuture<ReviewTask> tenantTwoOwner = new CompletableFuture<>();
        CompletableFuture<ReviewTask> tenantThreeOwner = new CompletableFuture<>();

        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            assertThat(coordinator.registerOwner("same-review", tenantTwoOwner)).isNull();
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(3L)) {
            assertThat(coordinator.registerOwner("same-review", tenantThreeOwner)).isNull();
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(2L)) {
            assertThat(coordinator.registerOwner("same-review", new CompletableFuture<>()))
                .isSameAs(tenantTwoOwner);
            coordinator.remove("same-review", tenantTwoOwner);
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(3L)) {
            assertThat(coordinator.registerOwner("same-review", new CompletableFuture<>()))
                .isSameAs(tenantThreeOwner);
            coordinator.remove("same-review", tenantThreeOwner);
        }
    }
}
