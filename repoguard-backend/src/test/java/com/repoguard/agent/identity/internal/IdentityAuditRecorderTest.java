package com.repoguard.agent.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import com.repoguard.agent.common.TrustedProxyProperties;
import com.repoguard.agent.entity.UserLoginAudit;
import com.repoguard.agent.mapper.UserLoginAuditMapper;
import com.repoguard.agent.web.AuditClientIpResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class IdentityAuditRecorderTest {

    private final UserLoginAuditMapper userLoginAuditMapper = Mockito.mock(UserLoginAuditMapper.class);
    private final IdentityAuditRecorder recorder = new IdentityAuditRecorder(userLoginAuditMapper, clientIpResolver());

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordsAuditWithCurrentRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.20");
        request.addHeader("X-Forwarded-For", "10.0.0.8");
        request.addHeader("User-Agent", "A".repeat(600));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.record(1001L, "admin", "LOGIN", "SUCCESS", null);

        ArgumentCaptor<UserLoginAudit> auditCaptor = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(auditCaptor.capture());
        UserLoginAudit audit = auditCaptor.getValue();
        assertThat(audit.getUserId()).isEqualTo(1001L);
        assertThat(audit.getAccount()).isEqualTo("admin");
        assertThat(audit.getEventType()).isEqualTo("LOGIN");
        assertThat(audit.getResult()).isEqualTo("SUCCESS");
        assertThat(audit.getFailureReason()).isNull();
        assertThat(audit.getClientIp()).isEqualTo("192.0.2.20");
        assertThat(audit.getUserAgent()).hasSize(512);
        assertThat(audit.getCreatedAt()).isNotNull();
    }

    @Test
    void recordsAuditWithoutRequestContext() {
        recorder.record(null, "admin", "LOGIN", "FAILURE", "bad credentials");

        ArgumentCaptor<UserLoginAudit> auditCaptor = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(auditCaptor.capture());
        UserLoginAudit audit = auditCaptor.getValue();
        assertThat(audit.getUserId()).isNull();
        assertThat(audit.getFailureReason()).isEqualTo("bad credentials");
        assertThat(audit.getClientIp()).isNull();
        assertThat(audit.getUserAgent()).isNull();
    }

    @Test
    void recordsForwardedClientIpFromTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Real-IP", "203.0.113.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder.record(1001L, "admin", "LOGIN", "SUCCESS", null);

        ArgumentCaptor<UserLoginAudit> auditCaptor = ArgumentCaptor.forClass(UserLoginAudit.class);
        verify(userLoginAuditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getClientIp()).isEqualTo("203.0.113.9");
    }

    @Test
    void constructorRequiresMapper() {
        assertThatThrownBy(() -> new IdentityAuditRecorder(null, clientIpResolver()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("userLoginAuditMapper");
    }

    @Test
    void constructorRequiresClientIpResolver() {
        assertThatThrownBy(() -> new IdentityAuditRecorder(userLoginAuditMapper, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("clientIpResolver");
    }

    private static AuditClientIpResolver clientIpResolver() {
        return new AuditClientIpResolver(
            new TrustedProxyClientIpResolver(new TrustedProxyProperties(), new SimpleMeterRegistry())
        );
    }
}
