package com.repoguard.agent.review.config;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import java.time.LocalDateTime;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ReviewPolicyAuditContextProvider {

    public AuditContext current() {
        AuthenticatedPrincipal principal = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object candidate = attributes.getRequest().getAttribute(
                RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL
            );
            if (candidate instanceof AuthenticatedPrincipal authenticatedPrincipal) {
                principal = authenticatedPrincipal;
            }
        }
        return new AuditContext(
            principal == null ? null : principal.id(),
            principal == null ? null : principal.username(),
            MDC.get("traceId"),
            LocalDateTime.now()
        );
    }

    public record AuditContext(
        Long actorUserId,
        String actorUsername,
        String traceId,
        LocalDateTime capturedAt
    ) {
    }
}
