package com.repoguard.agent.notification;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NotificationChannelAdapterRegistry {

    private final Map<String, NotificationChannelAdapter> adapters;

    public NotificationChannelAdapterRegistry(List<NotificationChannelAdapter> adapters) {
        this.adapters = adapters.stream()
            .collect(Collectors.toMap(adapter -> normalize(adapter.provider()), adapter -> adapter));
    }

    public NotificationChannelAdapter get(String provider) {
        NotificationChannelAdapter adapter = adapters.get(normalize(provider));
        if (adapter == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported notification provider: " + provider);
        }
        return adapter;
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    }
}
