package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import java.time.LocalDateTime;

class IntegrationConnectionCheckMarker {

    private final IntegrationConfigMapper integrationConfigMapper;

    IntegrationConnectionCheckMarker(IntegrationConfigMapper integrationConfigMapper) {
        this.integrationConfigMapper = integrationConfigMapper;
    }

    void markChecked(IntegrationConfig config, String error) {
        if (config == null || config.getId() == null) {
            return;
        }
        config.setLastCheckedAt(LocalDateTime.now());
        config.setLastError(error);
        config.setStatus(error == null ? "CONFIGURED" : "FAILED");
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
        if (error == null) {
            integrationConfigMapper.update(
                new UpdateWrapper<IntegrationConfig>()
                    .eq("id", config.getId())
                    .set("last_error", null)
            );
        }
    }
}
