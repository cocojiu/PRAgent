package com.repoguard.agent.service;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.PageResponse;

public interface NotificationBindingConfigService {

    PageResponse<NotificationBindingDto> listBindings(int page, int pageSize, String organization, String repository, String provider);

    NotificationBindingDto createBinding(NotificationBindingRequest request);

    NotificationBindingDto updateBinding(Long id, NotificationBindingRequest request);

    NotificationBindingDto updateBindingStatus(Long id, Boolean enabled);

    void deleteBinding(Long id);

    ConnectionTestResultDto testBinding(Long id);
}
