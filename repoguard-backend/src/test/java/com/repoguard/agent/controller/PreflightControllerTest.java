package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.preflight.PreflightPreviewService;
import com.repoguard.agent.security.AuthAttemptLimiter;
import com.repoguard.agent.security.PreflightCredentialService;
import com.repoguard.agent.web.AuditClientIpResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PreflightControllerTest {
    private final PreflightPreviewService previews = mock(PreflightPreviewService.class);
    private final PreflightCredentialService credentials = mock(PreflightCredentialService.class);
    private final AuthAttemptLimiter limiter = mock(AuthAttemptLimiter.class);
    private final AuditClientIpResolver ip = mock(AuditClientIpResolver.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final PreflightCredentialService.Grant grant =
        new PreflightCredentialService.Grant(7L, 3, 1, "fixture/repo", false, Long.MAX_VALUE, "fixture");
    private PreflightController controller(boolean enabled) {
        return new PreflightController(previews, credentials, limiter, ip, enabled);
    }

    @Test void disabledEndpointsRejectBeforeCredentialsBodyOrPrincipalUse() {
        var disabled = controller(false);
        assertThatThrownBy(() -> disabled.preview(request, response)).isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> disabled.issue(new PreflightController.CredentialRequest("fixture/repo", null),
            request, response)).isInstanceOf(BusinessException.class);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verifyNoInteractions(previews, credentials, limiter, ip);
    }

    @Test void invalidCredentialStopsBeforeBodyReadAndAdmission() {
        when(credentials.verify(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "fixture"));
        assertThatThrownBy(() -> controller(true).preview(request, response)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(previews, limiter, ip);
    }

    @Test void tenantOverrideRejectedEvenWithValidScopedCredential() {
        when(credentials.verify(null)).thenReturn(grant);
        request.addHeader("X-Tenant-Key", "other");
        assertThatThrownBy(() -> controller(true).preview(request, response)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(previews);
    }

    @Test void readsOnlyBoundedPayloadAndDelegatesExactRawBytesAndDigest() throws Exception {
        when(credentials.verify(null)).thenReturn(grant);
        when(ip.resolve(request)).thenReturn("127.0.0.1");
        request.addHeader("Idempotency-Key", "digest");
        request.setContent(new byte[3 * 1024 * 1024 + 10]);
        when(previews.preview(eq(grant), any(), eq("digest"))).thenReturn(Map.of("scope", "ONLINE_PREFLIGHT"));
        controller(true).preview(request, response);
        var body = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(previews).preview(eq(grant), body.capture(), eq("digest"));
        assertThat(body.getValue()).hasSize(3 * 1024 * 1024 + 1);
        verify(limiter).requireAllowed("preflight-preview", "7", "127.0.0.1");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test void absentModelRequestMintsOnlyRulesCredentialForAuthenticatedUser() {
        var principal = new AuthenticatedPrincipal(7L, "fixture", "VIEWER", Long.MAX_VALUE, 3, 1L);
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal);
        controller(true).issue(new PreflightController.CredentialRequest("fixture/repo", null), request, response);
        verify(previews).issue(principal, "fixture/repo", false);
        verifyNoInteractions(credentials);
    }
}
