package com.repoguard.agent.user;

import com.repoguard.agent.entity.AdminOperationAudit;
import com.repoguard.agent.mapper.AdminOperationAuditMapper;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.web.AuditClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminOperationAuditRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminOperationAuditRecorder.class);
    private final AdminOperationAuditMapper mapper;

    public AdminOperationAuditRecorder(AdminOperationAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request, int status, Exception failure) {
        try {
            AdminOperationAudit audit = new AdminOperationAudit();
            Object principal = request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE);
            if (principal instanceof AuthTokenService.AuthenticatedUser user) {
                audit.setActorUserId(user.id());
                audit.setActorUsername(truncate(user.username(), 255));
            }
            audit.setAction(truncate(request.getMethod() + " " + request.getRequestURI(), 128));
            audit.setTargetType("ADMIN_API");
            audit.setTargetId(truncate(request.getRequestURI(), 255));
            audit.setDiffJson("{\"requestBody\":\"redacted\"}");
            audit.setTraceId(truncate(MDC.get("traceId"), 128));
            audit.setClientIp(AuditClientIpResolver.resolve(request));
            audit.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
            audit.setResult(status < 400 && failure == null ? "SUCCESS" : "FAILED");
            audit.setFailureCategory(failure == null ? httpFailure(status) : truncate(failure.getClass().getSimpleName(), 128));
            audit.setCreatedAt(LocalDateTime.now());
            mapper.insert(audit);
        } catch (RuntimeException ex) {
            LOGGER.error("Unable to persist admin operation audit", ex);
        }
    }

    private String httpFailure(int status) {
        return status < 400 ? null : "HTTP_" + status;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
