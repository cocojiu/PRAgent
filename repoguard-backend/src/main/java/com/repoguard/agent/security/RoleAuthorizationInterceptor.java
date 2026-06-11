package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    public RoleAuthorizationInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requireRole = resolveRequireRole(handlerMethod);
        if (requireRole == null) {
            return true;
        }
        Object authenticatedUser = request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (!(authenticatedUser instanceof AuthTokenService.AuthenticatedUser user)) {
            writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication token is required");
            return false;
        }
        boolean allowed = Arrays.stream(requireRole.value()).anyMatch(role -> role.equalsIgnoreCase(user.role()));
        if (!allowed) {
            writeError(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Permission denied");
            return false;
        }
        return true;
    }

    private RequireRole resolveRequireRole(HandlerMethod handlerMethod) {
        RequireRole methodAnnotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return handlerMethod.getBeanType().getAnnotation(RequireRole.class);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode code, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
            "success", false,
            "code", code.code(),
            "message", message,
            "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
