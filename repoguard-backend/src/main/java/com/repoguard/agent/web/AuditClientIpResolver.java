package com.repoguard.agent.web;

import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AuditClientIpResolver {

    private static final int MAX_CLIENT_IP_LENGTH = 64;

    private final TrustedProxyClientIpResolver clientIpResolver;

    public AuditClientIpResolver(TrustedProxyClientIpResolver clientIpResolver) {
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver must not be null");
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return truncate(clientIpResolver.resolve(request), MAX_CLIENT_IP_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
