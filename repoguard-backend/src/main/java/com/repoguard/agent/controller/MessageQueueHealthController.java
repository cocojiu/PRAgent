package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.MessageQueueHealthService;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/message-queue")
public class MessageQueueHealthController {

    private final MessageQueueHealthService messageQueueHealthService;

    public MessageQueueHealthController(MessageQueueHealthService messageQueueHealthService) {
        this.messageQueueHealthService = messageQueueHealthService;
    }

    @GetMapping("/health")
    public ApiResponse<MessageQueueHealthResponse> getHealth() {
        return ApiResponse.ok(messageQueueHealthService.getHealth());
    }

    @PostMapping("/tasks/{taskId}/requeue")
    @RequireRole("ADMIN")
    public ApiResponse<MessageQueueRequeueResponse> requeueTask(@PathVariable @Min(1) Long taskId) {
        return ApiResponse.ok(messageQueueHealthService.requeueTask(taskId));
    }
}
