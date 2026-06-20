package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.repoguard.agent.notification.NotificationSendResult;
import com.repoguard.agent.service.NotificationBindingConnectionTestService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public ConnectionTestResultDto testBinding(Long id) {
        NotificationChannelBinding binding = requireBinding(id);
        NotificationSendResult result = adapterRegistry.get(binding.getProvider()).test(binding);
        ConnectionTestResultDto response = resultApplier.apply(binding, result, LocalDateTime.now());
        bindingMapper.updateById(binding);
        return response;
    }

    private NotificationChannelBinding requireBinding(Long id) {
        NotificationChannelBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification binding not found: " + id);
        }
        return binding;
    }

}
