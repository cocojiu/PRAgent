package com.repoguard.agent.notification;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NotificationChannelAdapterRegistry {

    private final Map<String, NotificationChannelAdapter> adapters;
    private final NotificationProviderKeyNormalizer providerKeyNormalizer;

    public NotificationChannelAdapterRegistry(
        List<NotificationChannelAdapter> adapters,
        NotificationProviderKeyNormalizer providerKeyNormalizer
    ) {
        this.providerKeyNormalizer = Objects.requireNonNull(providerKeyNormalizer, "providerKeyNormalizer");
        this.adapters = adapters.stream()
            .collect(Collectors.toMap(adapter -> providerKeyNormalizer.normalize(adapter.provider()), adapter -> adapter));
    }

    public NotificationChannelAdapter get(String provider) {
        NotificationChannelAdapter adapter = adapters.get(providerKeyNormalizer.normalize(provider));
        if (adapter == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported notification provider: " + provider);
        }
        return adapter;
    }
}
