package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.binding.NotificationBindingStatus;
import com.repoguard.agent.notification.NotificationChannelAdapter;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.time.LocalDateTime;
import static org.mockito.ArgumentMatchers.any;
import org.junit.jupiter.api.Test;

class NotificationBindingConnectionTestServiceImplTest {

    private final NotificationChannelBindingMapper bindingMapper = org.mockito.Mockito.mock(NotificationChannelBindingMapper.class);
    private final NotificationChannelAdapter adapter = org.mockito.Mockito.mock(NotificationChannelAdapter.class);
    private final NotificationChannelAdapterRegistry adapterRegistry = org.mockito.Mockito.mock(NotificationChannelAdapterRegistry.class);
    private final NotificationBindingConnectionTestServiceImpl service = new NotificationBindingConnectionTestServiceImpl(
        bindingMapper,
        adapterRegistry,
        new NotificationBindingConnectionTestResultApplier()
    );

    @Test
    void failedConnectionTestMarksBindingFailedAndStoresError() {
        NotificationChannelBinding binding = binding();
        when(bindingMapper.selectById(1L)).thenReturn(binding);
        when(adapterRegistry.get("DINGTALK")).thenReturn(adapter);
        when(adapter.test(binding)).thenReturn(NotificationSendResult.failed("request-1", "timeout"));

        var result = service.testBinding(1L);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).isEqualTo("timeout");
        assertThat(result.checkedAt()).isNotBlank();
        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.FAILED.code());
        assertThat(binding.getLastError()).isEqualTo("timeout");
        assertThat(binding.getLastCheckedAt()).isNotNull();
        assertThat(binding.getUpdatedAt()).isNotNull();
        verify(bindingMapper).update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class));
    }

    @Test
    void successfulConnectionTestMarksBindingConnectedAndClearsError() {
        NotificationChannelBinding binding = binding();
        binding.setLastError("old error");
        when(bindingMapper.selectById(1L)).thenReturn(binding);
        when(adapterRegistry.get("DINGTALK")).thenReturn(adapter);
        when(adapter.test(binding)).thenReturn(NotificationSendResult.success("request-1", "ok"));

        var result = service.testBinding(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("connected");
        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.CONNECTED.code());
        assertThat(binding.getLastError()).isNull();
        verify(bindingMapper).update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class));
    }

    @Test
    void failedConnectionTestTruncatesStoredError() {
        NotificationChannelBinding binding = binding();
        String longMessage = "x".repeat(1100);
        when(bindingMapper.selectById(1L)).thenReturn(binding);
        when(adapterRegistry.get("DINGTALK")).thenReturn(adapter);
        when(adapter.test(binding)).thenReturn(NotificationSendResult.failed("request-1", longMessage));

        service.testBinding(1L);

        assertThat(binding.getLastError()).hasSize(1024);
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(1L);
        binding.setProvider("DINGTALK");
        binding.setStatus(NotificationBindingStatus.CONFIGURED.code());
        binding.setUpdatedAt(LocalDateTime.parse("2026-07-13T12:00:00"));
        return binding;
    }
}
