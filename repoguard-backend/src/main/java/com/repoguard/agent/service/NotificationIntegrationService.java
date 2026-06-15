package com.repoguard.agent.service;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;

public interface NotificationIntegrationService {

    PageResponse<NotificationBindingDto> listBindings(int page, int pageSize, String organization, String repository, String provider);

    NotificationBindingDto createBinding(NotificationBindingRequest request);

    NotificationBindingDto updateBinding(Long id, NotificationBindingRequest request);

    NotificationBindingDto updateBindingStatus(Long id, Boolean enabled);

    void deleteBinding(Long id);

    ConnectionTestResultDto testBinding(Long id);

    PageResponse<NotificationEventDto> listEvents(int page, int pageSize, String status, Long taskId);

    NotificationEventDto retryEvent(Long id);

    PageResponse<NotificationDeliveryDto> listDeliveries(int page, int pageSize, String status, Long taskId);
}
