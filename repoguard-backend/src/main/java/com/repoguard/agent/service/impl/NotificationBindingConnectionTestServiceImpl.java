package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.notification.channel.NotificationChannelAdapterRegistry;
import com.repoguard.agent.service.NotificationBindingConnectionTestService;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class NotificationBindingConnectionTestServiceImpl implements NotificationBindingConnectionTestService {

    private final NotificationChannelBindingMapper bindingMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;
    private final NotificationBindingConnectionTestResultApplier resultApplier;

    public NotificationBindingConnectionTestServiceImpl(
        NotificationChannelBindingMapper bindingMapper,
        NotificationChannelAdapterRegistry adapterRegistry,
        NotificationBindingConnectionTestResultApplier resultApplier
    ) {
        this.bindingMapper = bindingMapper;
        this.adapterRegistry = adapterRegistry;
        this.resultApplier = resultApplier;
    }

    @Override
    public ConnectionTestResultDto testBinding(Long id) {
        NotificationChannelBinding binding = requireBinding(id);
        LocalDateTime expectedUpdatedAt = binding.getUpdatedAt();
        NotificationSendResult result = adapterRegistry.get(binding.getProvider()).test(binding);
        ConnectionTestResultDto response = resultApplier.apply(binding, result, LocalDateTime.now());
        updateResultIfUnchanged(binding, expectedUpdatedAt);
        return response;
    }

    private void updateResultIfUnchanged(NotificationChannelBinding binding, LocalDateTime expectedUpdatedAt) {
        if (expectedUpdatedAt == null) {
            return;
        }
        UpdateWrapper<NotificationChannelBinding> update = new UpdateWrapper<NotificationChannelBinding>()
            .eq("id", binding.getId())
            .eq("updated_at", expectedUpdatedAt)
            .set("last_checked_at", binding.getLastCheckedAt())
            .set("status", binding.getStatus())
            .set("last_error", binding.getLastError())
            .set("updated_at", binding.getUpdatedAt());
        bindingMapper.update(null, update);
    }

    private NotificationChannelBinding requireBinding(Long id) {
        NotificationChannelBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification binding not found: " + id);
        }
        return binding;
    }

}
