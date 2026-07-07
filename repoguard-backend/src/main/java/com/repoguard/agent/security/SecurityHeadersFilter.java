package com.repoguard.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    public static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String X_FRAME_OPTIONS = "X-Frame-Options";
    public static final String REFERRER_POLICY = "Referrer-Policy";
    public static final String PERMISSIONS_POLICY = "Permissions-Policy";
    public static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";

    static final String CONTENT_SECURITY_POLICY_VALUE = String.join(
        "; ",
        "default-src 'self'",
        "base-uri 'self'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "form-action 'self'",
        "img-src 'self' data: https:",
        "font-src 'self' data:",
        "style-src 'self' 'unsafe-inline'",
        "script-src 'self'",
        "connect-src 'self'"
    );
    static final String PERMISSIONS_POLICY_VALUE = String.join(
        ", ",
        "camera=()",
        "microphone=()",
        "geolocation=()",
        "payment=()"
    );
    static final String STRICT_TRANSPORT_SECURITY_VALUE = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        setHeaderIfMissing(response, CONTENT_SECURITY_POLICY, CONTENT_SECURITY_POLICY_VALUE);
        setHeaderIfMissing(response, X_CONTENT_TYPE_OPTIONS, "nosniff");
        setHeaderIfMissing(response, X_FRAME_OPTIONS, "DENY");
        setHeaderIfMissing(response, REFERRER_POLICY, "no-referrer");
        setHeaderIfMissing(response, PERMISSIONS_POLICY, PERMISSIONS_POLICY_VALUE);
        if (isSecureRequest(request)) {
            setHeaderIfMissing(response, STRICT_TRANSPORT_SECURITY, STRICT_TRANSPORT_SECURITY_VALUE);
        }
        filterChain.doFilter(request, response);
    }

    private void setHeaderIfMissing(HttpServletResponse response, String name, String value) {
        if (!response.containsHeader(name)) {
            response.setHeader(name, value);
        }
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        return request != null
            && (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
    }
}
