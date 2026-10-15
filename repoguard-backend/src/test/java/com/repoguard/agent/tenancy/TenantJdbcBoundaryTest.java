package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dashboard.FeedbackSummaryService;
import com.repoguard.agent.review.quality.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantJdbcBoundaryTest {
    @Test
    void enterpriseMissingContextCannotReadOrWriteDefaultTenant() {
        TenantProperties policy = new TenantProperties();
        policy.setEnabled(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LlmModelReleaseRepository releases = mock(LlmModelReleaseRepository.class);
        var summary = new FeedbackSummaryService(jdbc, policy);
        var safety = new LlmModelSafetyService(jdbc, releases, new ObjectMapper(), policy);
        assertThatThrownBy(summary::summary).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> safety.revoke(5L, new com.repoguard.agent.dto.LlmModelRollbackRequest("explicit reason"), "admin"))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc, releases);
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void personalDefaultAndTrustedEnterpriseScopesRemainIndependentAcrossInstances() {
        TenantProperties personal = new TenantProperties();
        TenantProperties enterprise = new TenantProperties();
        enterprise.setEnabled(true);
        assertThat(personal.currentTenantId()).isEqualTo(1L);
        assertThatThrownBy(enterprise::currentTenantId).isInstanceOf(IllegalStateException.class);
        try (var _ = TenantContext.withTenant(9L)) {
            assertThat(enterprise.currentTenantId()).isEqualTo(9L);
            assertThat(personal.currentTenantId()).isEqualTo(9L);
            try (var _ = TenantContext.withTenant(11L)) {
                assertThat(enterprise.currentTenantId()).isEqualTo(11L);
            }
            assertThat(enterprise.currentTenantId()).isEqualTo(9L);
        }
        assertThatThrownBy(enterprise::currentTenantId).isInstanceOf(IllegalStateException.class);
    }
}
