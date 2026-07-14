package com.repoguard.agent.web;

import com.repoguard.agent.user.AdminOperationAuditRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminOperationAuditInterceptor implements HandlerInterceptor {

    private final AdminOperationAuditRecorder recorder;

    public AdminOperationAuditInterceptor(AdminOperationAuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void afterCompletion(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler,
        Exception ex
    ) {
        if ("GET".equalsIgnoreCase(request.getMethod())
            || "HEAD".equalsIgnoreCase(request.getMethod())
            || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return;
        }
        recorder.record(request, response.getStatus(), ex);
    }
}
