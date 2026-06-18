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
import com.repoguard.agent.notification.NotificationBindingStatus;
import com.repoguard.agent.notification.NotificationChannelAdapterRegistry;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.NotificationBindingConnectionTestService;
import com.repoguard.agent.service.NotificationBindingConfigService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class NotificationBindingConfigServiceImpl implements NotificationBindingConfigService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String MASKED_SECRET = "******";

    private final NotificationChannelBindingMapper bindingMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;
    private final NotificationBindingConnectionTestService connectionTestService;
    private final SecretCryptoService secretCryptoService;

    public NotificationBindingConfigServiceImpl(
        NotificationChannelBindingMapper bindingMapper,
        NotificationChannelAdapterRegistry adapterRegistry,
        NotificationBindingConnectionTestService connectionTestService,
        SecretCryptoService secretCryptoService
    ) {
        this.bindingMapper = bindingMapper;
        this.adapterRegistry = adapterRegistry;
        this.connectionTestService = connectionTestService;
        this.secretCryptoService = secretCryptoService;
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
        return new PageResponse<>(result.getRecords().stream().map(this::toBindingDto).toList(), result.getTotal());
    }

    @Override
    @Transactional
    public NotificationBindingDto createBinding(NotificationBindingRequest request) {
        adapterRegistry.get(request.provider());
        LocalDateTime now = LocalDateTime.now();
        NotificationChannelBinding binding = new NotificationChannelBinding();
        apply(binding, request, now, true);
        binding.setCreatedAt(now);
        bindingMapper.insert(binding);
        return toBindingDto(binding);
    }

    @Override
    @Transactional
    public NotificationBindingDto updateBinding(Long id, NotificationBindingRequest request) {
        adapterRegistry.get(request.provider());
        NotificationChannelBinding binding = requireBinding(id);
        apply(binding, request, LocalDateTime.now(), false);
        bindingMapper.updateById(binding);
        return toBindingDto(binding);
    }

    @Override
    @Transactional
    public NotificationBindingDto updateBindingStatus(Long id, Boolean enabled) {
        NotificationChannelBinding binding = requireBinding(id);
        binding.setEnabled(Boolean.TRUE.equals(enabled));
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
        return toBindingDto(binding);
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

    private void apply(NotificationChannelBinding binding, NotificationBindingRequest request, LocalDateTime now, boolean create) {
        binding.setName(request.name().trim());
        binding.setProvider(normalizeProvider(request.provider()));
        binding.setOrganization(request.organization().trim());
        binding.setRepository(request.repository().trim());
        binding.setEnabled(Boolean.TRUE.equals(request.enabled()));
        String existingWebhookUrl = create ? null : secretCryptoService.decrypt(binding.getWebhookUrlValue());
        String existingSecret = create ? null : secretCryptoService.decrypt(binding.getSecretValue());
        String webhookUrl = resolveSecret(existingWebhookUrl, request.webhookUrl());
        if (!StringUtils.hasText(webhookUrl)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Webhook URL is required");
        }
        binding.setWebhookUrlValue(secretCryptoService.encrypt(webhookUrl));
        binding.setSecretValue(secretCryptoService.encrypt(resolveSecret(existingSecret, request.secret())));
        binding.setNotifyReviewCompleted(request.notifyReviewCompleted());
        binding.setNotifyReviewFailed(request.notifyReviewFailed());
        binding.setNotifyHumanReviewRequired(request.notifyHumanReviewRequired());
        binding.setNotifyGithubComment(request.notifyGithubComment());
        binding.setStatus(NotificationBindingStatus.CONFIGURED.code());
        binding.setLastError(null);
        binding.setUpdatedAt(now);
    }

    private NotificationChannelBinding requireBinding(Long id) {
        NotificationChannelBinding binding = bindingMapper.selectById(id);
        if (binding == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Notification binding not found: " + id);
        }
        return binding;
    }

    private NotificationBindingDto toBindingDto(NotificationChannelBinding binding) {
        return new NotificationBindingDto(
            binding.getId(),
            binding.getName(),
            binding.getProvider(),
            binding.getOrganization(),
            binding.getRepository(),
            binding.getEnabled(),
            hasSecret(binding.getWebhookUrlValue()) ? MASKED_SECRET : null,
            hasSecret(binding.getSecretValue()) ? MASKED_SECRET : null,
            binding.getNotifyReviewCompleted(),
            binding.getNotifyReviewFailed(),
            binding.getNotifyHumanReviewRequired(),
            binding.getNotifyGithubComment(),
            binding.getStatus(),
            format(binding.getLastCheckedAt()),
            binding.getLastError(),
            format(binding.getCreatedAt()),
            format(binding.getUpdatedAt())
        );
    }

    private String resolveSecret(String existing, String requested) {
        if (MASKED_SECRET.equals(requested)) {
            return existing;
        }
        return trim(requested);
    }

    private boolean hasSecret(String encrypted) {
        return StringUtils.hasText(secretCryptoService.decrypt(encrypted));
    }

    private String normalizeProvider(String provider) {
        return trim(provider).toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
