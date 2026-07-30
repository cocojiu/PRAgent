package com.repoguard.agent.identity.internal;

import com.repoguard.agent.entity.UserLoginAudit;
import com.repoguard.agent.mapper.UserLoginAuditMapper;
import com.repoguard.agent.web.AuditClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public final class IdentityAuditRecorder {

    private static final int USER_AGENT_MAX_LENGTH = 512;

    private final UserLoginAuditMapper userLoginAuditMapper;
    private final AuditClientIpResolver clientIpResolver;

    public IdentityAuditRecorder(UserLoginAuditMapper userLoginAuditMapper, AuditClientIpResolver clientIpResolver) {
        this.userLoginAuditMapper = Objects.requireNonNull(
            userLoginAuditMapper,
            "userLoginAuditMapper must not be null"
        );
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver must not be null");
    }

    public void record(Long userId, String account, String eventType, String result, String failureReason) {
        UserLoginAudit audit = new UserLoginAudit();
        audit.setUserId(userId);
        audit.setAccount(account);
        audit.setEventType(eventType);
        audit.setResult(result);
        audit.setFailureReason(failureReason);
        audit.setCreatedAt(LocalDateTime.now());

        HttpServletRequest request = currentRequest();
        if (request != null) {
            audit.setClientIp(clientIpResolver.resolve(request));
            audit.setUserAgent(truncate(request.getHeader("User-Agent"), USER_AGENT_MAX_LENGTH));
        }
        userLoginAuditMapper.insert(audit);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
