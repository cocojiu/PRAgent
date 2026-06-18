package com.repoguard.agent.service;

import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;

public interface NotificationEventQueryService {

    PageResponse<NotificationEventDto> listEvents(int page, int pageSize, String status, Long taskId);

    NotificationEventDto retryEvent(Long id);

    PageResponse<NotificationDeliveryDto> listDeliveries(int page, int pageSize, String status, Long taskId);
}
