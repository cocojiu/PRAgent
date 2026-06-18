package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.NotificationBindingStatus;
import com.repoguard.agent.notification.NotificationChannelAdapter;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.repoguard.agent.service.NotificationBindingConnectionTestService;
import com.repoguard.agent.security.SecretCryptoService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationBindingConfigServiceImplTest {

    private final NotificationChannelBindingMapper bindingMapper = org.mockito.Mockito.mock(NotificationChannelBindingMapper.class);
    private final NotificationChannelAdapter adapter = org.mockito.Mockito.mock(NotificationChannelAdapter.class);
    private final NotificationChannelAdapterRegistry adapterRegistry = org.mockito.Mockito.mock(NotificationChannelAdapterRegistry.class);
    private final NotificationBindingConnectionTestService connectionTestService = org.mockito.Mockito.mock(NotificationBindingConnectionTestService.class);
    private final SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
    private final NotificationBindingConfigServiceImpl service = new NotificationBindingConfigServiceImpl(
        bindingMapper,
        adapterRegistry,
        connectionTestService,
        secretCryptoService
    );

    @Test
    void listBindingsExcludesSoftDeletedBindingsAndMasksSecrets() {
        Page<NotificationChannelBinding> page = Page.of(1, 20);
        page.setRecords(List.of(binding()));
        page.setTotal(1);
        when(bindingMapper.selectPage(any(), any())).thenReturn(page);
        when(secretCryptoService.decrypt("enc:webhook")).thenReturn("https://example.com/webhook");
        when(secretCryptoService.decrypt("enc:secret")).thenReturn("secret");

        var result = service.listBindings(1, 20, null, null, null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().webhookUrl()).isEqualTo("******");
        assertThat(result.items().getFirst().secret()).isEqualTo("******");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<NotificationChannelBinding>> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(bindingMapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("<>");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs()).containsValue(NotificationBindingStatus.DELETED.code());
    }

    @Test
    void updateBindingKeepsExistingSecretsWhenRequestContainsMask() {
        NotificationChannelBinding binding = binding();
        when(bindingMapper.selectById(1L)).thenReturn(binding);
        when(adapterRegistry.get("DINGTALK")).thenReturn(adapter);
        when(secretCryptoService.decrypt("enc:webhook")).thenReturn("https://old.example/webhook");
        when(secretCryptoService.decrypt("enc:secret")).thenReturn("old-secret");
        when(secretCryptoService.encrypt("https://old.example/webhook")).thenReturn("enc:new-webhook");
        when(secretCryptoService.encrypt("old-secret")).thenReturn("enc:new-secret");

        var result = service.updateBinding(1L, request("******", "******"));

        assertThat(result.provider()).isEqualTo("DINGTALK");
        assertThat(binding.getWebhookUrlValue()).isEqualTo("enc:new-webhook");
        assertThat(binding.getSecretValue()).isEqualTo("enc:new-secret");
        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.CONFIGURED.code());
        verify(bindingMapper).updateById(binding);
    }

    @Test
    void updateBindingStatusDisablesBinding() {
        NotificationChannelBinding binding = binding();
        when(bindingMapper.selectById(1L)).thenReturn(binding);

        service.updateBindingStatus(1L, false);

        assertThat(binding.getEnabled()).isFalse();
        assertThat(binding.getUpdatedAt()).isNotNull();
        verify(bindingMapper).updateById(binding);
    }

    @Test
    void deleteBindingSoftDeletesBinding() {
        NotificationChannelBinding binding = binding();
        when(bindingMapper.selectById(1L)).thenReturn(binding);

        service.deleteBinding(1L);

        assertThat(binding.getEnabled()).isFalse();
        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.DELETED.code());
        assertThat(binding.getUpdatedAt()).isNotNull();
        verify(bindingMapper).updateById(binding);
    }

    @Test
    void testBindingDelegatesToConnectionTestService() {
        ConnectionTestResultDto expected = new ConnectionTestResultDto(false, "failed", "timeout", "2026-06-18 10:00:00");
        when(connectionTestService.testBinding(1L)).thenReturn(expected);

        var result = service.testBinding(1L);

        assertThat(result).isSameAs(expected);
        verify(connectionTestService).testBinding(1L);
    }

    private NotificationBindingRequest request(String webhookUrl, String secret) {
        return new NotificationBindingRequest(
            "DingTalk",
            "dingtalk",
            "octocat",
            "Hello-World",
            true,
            webhookUrl,
            secret,
            true,
            true,
            true,
            true
        );
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
        binding.setStatus(NotificationBindingStatus.CONFIGURED.code());
        return binding;
    }
}
