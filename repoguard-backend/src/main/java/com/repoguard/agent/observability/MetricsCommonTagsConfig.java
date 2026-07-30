package com.repoguard.agent.observability;

import com.repoguard.agent.config.RuntimeRoleContract;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stamps every meter with the process role so a split deployment can tell API
 * and Worker series apart in the metrics store.
 *
 * <p>The role comes from {@link RuntimeRoleContract} rather than from an
 * {@code app.runtime.role} placeholder: that property defaults to an empty
 * string, and only the contract applies the legacy-flag fallback that resolves
 * the effective role.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsCommonTagsConfig {

    @Bean
    public MeterFilter repoGuardCommonTags(RuntimeRoleContract runtimeRoleContract) {
        return MeterFilter.commonTags(List.of(
            Tag.of("application", "repoguard-backend"),
            Tag.of("role", runtimeRoleContract.role().value())
        ));
    }
}
