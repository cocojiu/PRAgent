package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.SystemSettingLog;
import com.repoguard.agent.mapper.SystemSettingLogMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class MessageQueueAuditRecorder {

    private final SystemSettingLogMapper systemSettingLogMapper;

    MessageQueueAuditRecorder(SystemSettingLogMapper systemSettingLogMapper) {
        this.systemSettingLogMapper = systemSettingLogMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRequeue(Long taskId, String status, String detail) {
        SystemSettingLog log = new SystemSettingLog();
        log.setOperator("admin-api-key");
        log.setAction(truncate("MQ requeue task #" + taskId + ": " + detail));
        log.setStatus(status);
        log.setCreatedAt(LocalDateTime.now());
        systemSettingLogMapper.insert(log);
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
