package com.repoguard.agent.notification.facade;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.service.NotificationBindingConfigService;
import com.repoguard.agent.service.NotificationEventQueryService;
import com.repoguard.agent.service.NotificationIntegrationService;
import org.springframework.stereotype.Service;

@Service
public class NotificationIntegrationServiceImpl implements NotificationIntegrationService {

    private final NotificationBindingConfigService bindingConfigService;
    private final NotificationEventQueryService eventQueryService;

    public NotificationIntegrationServiceImpl(
        NotificationBindingConfigService bindingConfigService,
        NotificationEventQueryService eventQueryService
    ) {
        this.bindingConfigService = bindingConfigService;
        this.eventQueryService = eventQueryService;
    }

    @Override
    public PageResponse<NotificationBindingDto> listBindings(int page, int pageSize, String organization, String repository, String provider) {
        return bindingConfigService.listBindings(page, pageSize, organization, repository, provider);
    }

    @Override
    public NotificationBindingDto createBinding(NotificationBindingRequest request) {
        return bindingConfigService.createBinding(request);
    }

    @Override
    public NotificationBindingDto updateBinding(Long id, NotificationBindingRequest request) {
        return bindingConfigService.updateBinding(id, request);
    }

    @Override
    public NotificationBindingDto updateBindingStatus(Long id, Boolean enabled) {
        return bindingConfigService.updateBindingStatus(id, enabled);
    }

    @Override
    public void deleteBinding(Long id) {
        bindingConfigService.deleteBinding(id);
    }

    @Override
    public ConnectionTestResultDto testBinding(Long id) {
        return bindingConfigService.testBinding(id);
    }

    @Override
    public PageResponse<NotificationEventDto> listEvents(int page, int pageSize, String status, Long taskId) {
        return eventQueryService.listEvents(page, pageSize, status, taskId);
    }

    @Override
    public NotificationEventDto retryEvent(Long id) {
        return eventQueryService.retryEvent(id);
    }

    @Override
    public PageResponse<NotificationDeliveryDto> listDeliveries(int page, int pageSize, String status, Long taskId) {
        return eventQueryService.listDeliveries(page, pageSize, status, taskId);
    }
}
