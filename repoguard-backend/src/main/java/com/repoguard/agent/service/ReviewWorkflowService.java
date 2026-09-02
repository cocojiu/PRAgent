package com.repoguard.agent.service;

import com.repoguard.agent.dto.NotificationReadRequest;
import com.repoguard.agent.dto.NotificationReportDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewAssignmentRequest;
import com.repoguard.agent.dto.ReviewBotCommandRequest;
import com.repoguard.agent.dto.ReviewBotCommandResponse;
import com.repoguard.agent.dto.ReviewEscalationResponse;
import com.repoguard.agent.dto.ReviewWorkflowItemDto;
import java.util.List;

public interface ReviewWorkflowService {

    PageResponse<ReviewWorkflowItemDto> listQueue(int page, int pageSize, String assignee, Boolean overdue);

    ReviewWorkflowItemDto assign(Long taskId, ReviewAssignmentRequest request, String operator);

    ReviewEscalationResponse escalateOverdue();

    ReviewBotCommandResponse executeBotCommand(String provider, ReviewBotCommandRequest request, String actor);

    void markNotificationRead(NotificationReadRequest request, String readerKey);

    List<String> listReadNotificationKeys(String readerKey);

    NotificationReportDto report(String period);
}
