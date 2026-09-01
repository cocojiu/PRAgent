package com.repoguard.agent.tenancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantProperties properties;
    private final TenantResolutionService resolutionService;
    private final ObjectMapper objectMapper;

    public TenantContextFilter(
        TenantProperties properties,
        TenantResolutionService resolutionService,
        ObjectMapper objectMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.resolutionService = Objects.requireNonNull(resolutionService, "resolutionService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (isControlPlaneRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        Object candidate = request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL);
        if (!(candidate instanceof AuthenticatedPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }
        TenantMembershipView membership;
        try {
            membership = resolutionService.resolve(
                principal.id(),
                principal.tenantId(),
                request.getHeader(properties.getHeaderName())
            );
        } catch (BusinessException exception) {
            writeTenantError(response, exception);
            return;
        }
        AuthenticatedPrincipal tenantPrincipal = principal.withTenant(
            membership.tenantId(),
            membership.role()
        );
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, tenantPrincipal);
        try (TenantContext.Scope _ = TenantContext.withTenant(membership.tenantId())) {
            filterChain.doFilter(request, response);
        }
    }

    private boolean isControlPlaneRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/api/v1/enterprise/tenants")
            || path.startsWith("/api/v1/enterprise/tenants/");
    }

    private void writeTenantError(HttpServletResponse response, BusinessException exception) throws IOException {
        int status = exception.getErrorCode() == ErrorCode.BAD_REQUEST ? 400 : 403;
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
            "success", false,
            "code", exception.getErrorCode().code(),
            "message", exception.getMessage(),
            "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
