package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.service.MessageQueueHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
