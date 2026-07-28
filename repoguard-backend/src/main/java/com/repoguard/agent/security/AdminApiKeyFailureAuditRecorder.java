package com.repoguard.agent.security;

import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import com.repoguard.agent.entity.AdminOperationAudit;
import com.repoguard.agent.mapper.AdminOperationAuditMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminApiKeyFailureAuditRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminApiKeyFailureAuditRecorder.class);

    private final AdminOperationAuditMapper mapper;
    private final TrustedProxyClientIpResolver clientIpResolver;

    public AdminApiKeyFailureAuditRecorder(
        AdminOperationAuditMapper mapper,
        TrustedProxyClientIpResolver clientIpResolver
    ) {
        this.mapper = mapper;
        this.clientIpResolver = clientIpResolver;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request, String failureCategory) {
        try {
            AdminOperationAudit audit = new AdminOperationAudit();
            audit.setActorUsername("anonymous");
            audit.setAction(truncate(request.getMethod() + " " + request.getRequestURI(), 128));
            audit.setTargetType("ADMIN_API_KEY");
            audit.setTargetId(truncate(request.getRequestURI(), 255));
            audit.setDiffJson("{\"credential\":\"redacted\"}");
            audit.setTraceId(truncate(MDC.get("traceId"), 128));
            audit.setClientIp(truncate(clientIpResolver.resolve(request), 64));
            audit.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
            audit.setResult("FAILED");
            audit.setFailureCategory(truncate(failureCategory, 128));
            audit.setCreatedAt(LocalDateTime.now());
            mapper.insert(audit);
        } catch (RuntimeException exception) {
            LOGGER.error("Unable to persist Admin API key authentication failure audit", exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
