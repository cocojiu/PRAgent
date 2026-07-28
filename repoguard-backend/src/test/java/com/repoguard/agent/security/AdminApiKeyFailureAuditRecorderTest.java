package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import com.repoguard.agent.entity.AdminOperationAudit;
import com.repoguard.agent.mapper.AdminOperationAuditMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminApiKeyFailureAuditRecorderTest {

    @Test
    void recordsFailureMetadataWithoutPersistingCredentialValue() {
        AdminOperationAuditMapper mapper = mock(AdminOperationAuditMapper.class);
        TrustedProxyClientIpResolver resolver = mock(TrustedProxyClientIpResolver.class);
        when(resolver.resolve(any())).thenReturn("192.0.2.10");
        AdminApiKeyFailureAuditRecorder recorder = new AdminApiKeyFailureAuditRecorder(mapper, resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.addHeader("X-RepoGuard-Admin-Key", "top-secret-admin-api-key");
        request.addHeader("User-Agent", "contract-test");

        recorder.record(request, "ADMIN_API_KEY_INVALID");

        ArgumentCaptor<AdminOperationAudit> captor = ArgumentCaptor.forClass(AdminOperationAudit.class);
        verify(mapper).insert(captor.capture());
        AdminOperationAudit audit = captor.getValue();
        assertThat(audit.getTargetType()).isEqualTo("ADMIN_API_KEY");
        assertThat(audit.getFailureCategory()).isEqualTo("ADMIN_API_KEY_INVALID");
        assertThat(audit.getClientIp()).isEqualTo("192.0.2.10");
        assertThat(audit.getDiffJson()).doesNotContain("top-secret-admin-api-key");
    }
}
