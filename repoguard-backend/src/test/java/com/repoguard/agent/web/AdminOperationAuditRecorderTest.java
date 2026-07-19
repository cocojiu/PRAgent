package com.repoguard.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.entity.AdminOperationAudit;
import com.repoguard.agent.mapper.AdminOperationAuditMapper;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminOperationAuditRecorderTest {

    private final AdminOperationAuditMapper mapper = Mockito.mock(AdminOperationAuditMapper.class);
    private final AdminOperationAuditRecorder recorder = new AdminOperationAuditRecorder(mapper);

    @AfterEach
    void clearMdc() {
        MDC.remove("traceId");
    }

    @Test
    void recordsAuthenticatedAdminRequestContext() {
        MockHttpServletRequest request = request();
        request.setAttribute(
            AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
            new AuthTokenService.AuthenticatedUser(1001L, "admin", "ADMIN", 9999999999L)
        );
        request.addHeader("X-Real-IP", "203.0.113.10");
        request.addHeader("User-Agent", "JUnit");
        MDC.put("traceId", "trace-42");

        recorder.record(request, 200, null);

        ArgumentCaptor<AdminOperationAudit> auditCaptor = ArgumentCaptor.forClass(AdminOperationAudit.class);
        verify(mapper).insert(auditCaptor.capture());
        AdminOperationAudit audit = auditCaptor.getValue();
        assertThat(audit.getActorUserId()).isEqualTo(1001L);
        assertThat(audit.getActorUsername()).isEqualTo("admin");
        assertThat(audit.getAction()).isEqualTo("POST /api/v1/config/settings");
        assertThat(audit.getTargetType()).isEqualTo("ADMIN_API");
        assertThat(audit.getClientIp()).isEqualTo("203.0.113.10");
        assertThat(audit.getUserAgent()).isEqualTo("JUnit");
        assertThat(audit.getTraceId()).isEqualTo("trace-42");
        assertThat(audit.getResult()).isEqualTo("SUCCESS");
        assertThat(audit.getFailureCategory()).isNull();
        assertThat(audit.getCreatedAt()).isNotNull();
    }

    @Test
    void recordsExceptionTypeAsFailureCategory() {
        recorder.record(request(), 500, new IllegalStateException("failed"));

        ArgumentCaptor<AdminOperationAudit> auditCaptor = ArgumentCaptor.forClass(AdminOperationAudit.class);
        verify(mapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo("FAILED");
        assertThat(auditCaptor.getValue().getFailureCategory()).isEqualTo("IllegalStateException");
    }

    @Test
    void auditPersistenceFailureDoesNotEscapeToTheRequest() {
        doThrow(new IllegalStateException("database unavailable"))
            .when(mapper)
            .insert(any(AdminOperationAudit.class));

        assertThatCode(() -> recorder.record(request(), 204, null)).doesNotThrowAnyException();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/config/settings");
    }
}
