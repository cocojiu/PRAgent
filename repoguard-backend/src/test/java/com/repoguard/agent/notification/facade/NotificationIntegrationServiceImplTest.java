package com.repoguard.agent.notification.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.service.NotificationBindingConfigService;
import com.repoguard.agent.service.NotificationEventQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationIntegrationServiceImplTest {

    private final NotificationBindingConfigService bindingConfigService = org.mockito.Mockito.mock(NotificationBindingConfigService.class);
    private final NotificationEventQueryService eventQueryService = org.mockito.Mockito.mock(NotificationEventQueryService.class);
    private final NotificationIntegrationServiceImpl service = new NotificationIntegrationServiceImpl(
        bindingConfigService,
        eventQueryService
    );

    @Test
    void listBindingsDelegatesToBindingConfigService() {
        PageResponse<NotificationBindingDto> page = new PageResponse<>(List.of(), 0);
        when(bindingConfigService.listBindings(1, 20, "octocat", "Hello-World", "dingtalk")).thenReturn(page);

        var result = service.listBindings(1, 20, "octocat", "Hello-World", "dingtalk");

        assertThat(result).isSameAs(page);
        verify(bindingConfigService).listBindings(1, 20, "octocat", "Hello-World", "dingtalk");
    }

    @Test
    void listEventsDelegatesToEventQueryService() {
        PageResponse<NotificationEventDto> page = new PageResponse<>(List.of(), 0);
        when(eventQueryService.listEvents(1, 20, "pending", 100L)).thenReturn(page);

        var result = service.listEvents(1, 20, "pending", 100L);

        assertThat(result).isSameAs(page);
        verify(eventQueryService).listEvents(1, 20, "pending", 100L);
    }
}
