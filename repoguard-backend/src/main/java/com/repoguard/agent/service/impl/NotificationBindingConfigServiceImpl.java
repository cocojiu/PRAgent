package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.notification.binding.NotificationBindingStatus;
import com.repoguard.agent.notification.channel.NotificationChannelAdapterRegistry;
import com.repoguard.agent.service.NotificationBindingConnectionTestService;
import com.repoguard.agent.service.NotificationBindingConfigService;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class NotificationBindingConfigServiceImpl implements NotificationBindingConfigService {

    private final NotificationChannelBindingMapper bindingMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;
    private final NotificationBindingConnectionTestService connectionTestService;
    private final NotificationBindingRequestApplier requestApplier;
    private final NotificationBindingResponseAssembler responseAssembler;

    public NotificationBindingConfigServiceImpl(
        NotificationChannelBindingMapper bindingMapper,
        NotificationChannelAdapterRegistry adapterRegistry,
        NotificationBindingConnectionTestService connectionTestService,
        NotificationBindingRequestApplier requestApplier,
        NotificationBindingResponseAssembler responseAssembler
    ) {
        this.bindingMapper = bindingMapper;
        this.adapterRegistry = adapterRegistry;
        this.connectionTestService = connectionTestService;
        this.requestApplier = requestApplier;
        this.responseAssembler = responseAssembler;
    }

    @Override
    public PageResponse<NotificationBindingDto> listBindings(int page, int pageSize, String organization, String repository, String provider) {
        String normalizedProvider = StringUtils.hasText(provider) ? normalizeProvider(provider) : null;
        Page<NotificationChannelBinding> result = bindingMapper.selectPage(
            Page.of(page, pageSize),
            new QueryWrapper<NotificationChannelBinding>()
                .eq(StringUtils.hasText(organization), "organization", trim(organization))
                .eq(StringUtils.hasText(repository), "repository", trim(repository))
                .eq(normalizedProvider != null, "provider", normalizedProvider)
                .ne("status", NotificationBindingStatus.DELETED.code())
                .orderByDesc("updated_at")
        );
        return new PageResponse<>(result.getRecords().stream().map(responseAssembler::assemble).toList(), result.getTotal());
    }

    @Override
    @Transactional
    public NotificationBindingDto createBinding(NotificationBindingRequest request) {
        adapterRegistry.get(request.provider());
        LocalDateTime now = LocalDateTime.now();
        NotificationChannelBinding binding = new NotificationChannelBinding();
        requestApplier.apply(binding, request, now, true);
        binding.setCreatedAt(now);
        bindingMapper.insert(binding);
        return responseAssembler.assemble(binding);
    }

    @Override
    @Transactional
    public NotificationBindingDto updateBinding(Long id, NotificationBindingRequest request) {
        adapterRegistry.get(request.provider());
        NotificationChannelBinding binding = requireBinding(id);
        requestApplier.apply(binding, request, LocalDateTime.now(), false);
        bindingMapper.updateById(binding);
        return responseAssembler.assemble(binding);
    }

    @Override
    @Transactional
    public NotificationBindingDto updateBindingStatus(Long id, Boolean enabled) {
        NotificationChannelBinding binding = requireBinding(id);
        binding.setEnabled(Boolean.TRUE.equals(enabled));
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
        return responseAssembler.assemble(binding);
    }

    @Override
    @Transactional
    public void deleteBinding(Long id) {
        NotificationChannelBinding binding = requireBinding(id);
        binding.setEnabled(false);
        binding.setStatus(NotificationBindingStatus.DELETED.code());
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
    }

    @Override
    public ConnectionTestResultDto testBinding(Long id) {
        return connectionTestService.testBinding(id);
    }

    private NotificationChannelBinding requireBinding(Long id) {
        NotificationChannelBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification binding not found: " + id);
        }
        return binding;
    }

    private String normalizeProvider(String provider) {
        return trim(provider).toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

}
