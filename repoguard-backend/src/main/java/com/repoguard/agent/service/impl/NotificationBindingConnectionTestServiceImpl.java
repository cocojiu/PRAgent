package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.NotificationBindingStatus;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.repoguard.agent.notification.NotificationSendResult;
import com.repoguard.agent.service.NotificationBindingConnectionTestService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationBindingConnectionTestServiceImpl implements NotificationBindingConnectionTestService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationChannelBindingMapper bindingMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;

    public NotificationBindingConnectionTestServiceImpl(
        NotificationChannelBindingMapper bindingMapper,
        NotificationChannelAdapterRegistry adapterRegistry
    ) {
        this.bindingMapper = bindingMapper;
        this.adapterRegistry = adapterRegistry;
    }

    @Override
    @Transactional
    public ConnectionTestResultDto testBinding(Long id) {
        NotificationChannelBinding binding = requireBinding(id);
        NotificationSendResult result = adapterRegistry.get(binding.getProvider()).test(binding);
        binding.setLastCheckedAt(LocalDateTime.now());
        binding.setStatus(result.success()
            ? NotificationBindingStatus.CONNECTED.code()
            : NotificationBindingStatus.FAILED.code());
        binding.setLastError(result.success() ? null : truncate(result.message(), 1024));
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
        return new ConnectionTestResultDto(result.success(), result.success() ? "connected" : "failed", result.message(), format(binding.getLastCheckedAt()));
    }

    private NotificationChannelBinding requireBinding(Long id) {
        NotificationChannelBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification binding not found: " + id);
        }
        return binding;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
