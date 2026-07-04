package com.repoguard.agent.notification;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
class NotificationProviderKeyNormalizer {

    String normalize(String provider) {
        return provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    }
}
