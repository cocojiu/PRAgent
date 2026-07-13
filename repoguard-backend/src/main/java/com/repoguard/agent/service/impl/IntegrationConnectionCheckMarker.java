package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class IntegrationConnectionCheckMarker {

    private final IntegrationConfigMapper integrationConfigMapper;

    IntegrationConnectionCheckMarker(IntegrationConfigMapper integrationConfigMapper) {
        this.integrationConfigMapper = integrationConfigMapper;
    }

    void markChecked(IntegrationConfig config, String error) {
        if (config == null || config.getId() == null || config.getUpdatedAt() == null) {
            return;
        }
        LocalDateTime expectedUpdatedAt = config.getUpdatedAt();
        LocalDateTime checkedAt = LocalDateTime.now();
        integrationConfigMapper.update(
            null,
                new UpdateWrapper<IntegrationConfig>()
                .eq("id", config.getId())
                .eq("updated_at", expectedUpdatedAt)
                .set("last_checked_at", checkedAt)
                .set("last_error", error)
                .set("status", error == null ? "CONFIGURED" : "FAILED")
                .set("updated_at", checkedAt)
        );
        config.setLastCheckedAt(checkedAt);
        config.setLastError(error);
        config.setStatus(error == null ? "CONFIGURED" : "FAILED");
        config.setUpdatedAt(checkedAt);
    }
}
