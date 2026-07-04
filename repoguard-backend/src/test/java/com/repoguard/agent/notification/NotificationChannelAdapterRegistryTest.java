package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationChannelAdapterRegistryTest {

    private final NotificationProviderKeyNormalizer normalizer = new NotificationProviderKeyNormalizer();

    @Test
    void getFindsAdapterUsingNormalizedProviderKey() {
        NotificationChannelAdapter adapter = org.mockito.Mockito.mock(NotificationChannelAdapter.class);
        when(adapter.provider()).thenReturn("DINGTALK");
        NotificationChannelAdapterRegistry registry = new NotificationChannelAdapterRegistry(List.of(adapter), normalizer);

        assertThat(registry.get(" dingtalk ")).isSameAs(adapter);
    }

    @Test
    void getRejectsUnsupportedProvider() {
        NotificationChannelAdapterRegistry registry = new NotificationChannelAdapterRegistry(List.of(), normalizer);

        assertThatThrownBy(() -> registry.get("unknown"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unsupported notification provider: unknown");
    }
}
