package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.security.SecretCryptoService;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationIntegrationServiceImplTest {

    private final NotificationChannelBindingMapper bindingMapper = org.mockito.Mockito.mock(NotificationChannelBindingMapper.class);
    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationDeliveryLogMapper deliveryLogMapper = org.mockito.Mockito.mock(NotificationDeliveryLogMapper.class);
    private final NotificationChannelAdapterRegistry adapterRegistry = org.mockito.Mockito.mock(NotificationChannelAdapterRegistry.class);
    private final NotificationDispatchService dispatchService = org.mockito.Mockito.mock(NotificationDispatchService.class);
    private final SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
    private final NotificationIntegrationServiceImpl service = new NotificationIntegrationServiceImpl(
        bindingMapper,
        eventMapper,
        deliveryLogMapper,
        adapterRegistry,
        dispatchService,
        secretCryptoService
    );

    @Test
    void listBindingsAllowsMissingProviderFilter() {
        Page<NotificationChannelBinding> page = Page.of(1, 20);
        page.setRecords(List.of(binding()));
        page.setTotal(1);
        when(bindingMapper.selectPage(any(), any())).thenReturn(page);
        when(secretCryptoService.decrypt("enc:webhook")).thenReturn("https://example.com/webhook");
        when(secretCryptoService.decrypt("enc:secret")).thenReturn("secret");

        var result = service.listBindings(1, 20, null, null, null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().provider()).isEqualTo("DINGTALK");
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(1L);
        binding.setName("DingTalk");
        binding.setProvider("DINGTALK");
        binding.setOrganization("octocat");
        binding.setRepository("Hello-World");
        binding.setEnabled(true);
        binding.setWebhookUrlValue("enc:webhook");
        binding.setSecretValue("enc:secret");
        binding.setNotifyReviewCompleted(true);
        binding.setNotifyReviewFailed(true);
        binding.setNotifyHumanReviewRequired(true);
        binding.setNotifyGithubComment(true);
        binding.setStatus("CONFIGURED");
        return binding;
    }
}
