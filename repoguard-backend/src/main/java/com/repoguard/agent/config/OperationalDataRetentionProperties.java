package com.repoguard.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.operational-data-retention")
public class OperationalDataRetentionProperties {

    private boolean enabled = true;
    private int batchSize = 500;
    private int maxBatchesPerRun = 20;
    private int refreshTokenDays = 30;
    private int loginAuditDays = 180;
    private int operationAuditDays = 365;
    private int systemSettingLogDays = 365;
    private int notificationLogDays = 90;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxBatchesPerRun() { return maxBatchesPerRun; }
    public void setMaxBatchesPerRun(int maxBatchesPerRun) { this.maxBatchesPerRun = maxBatchesPerRun; }
    public int getRefreshTokenDays() { return refreshTokenDays; }
    public void setRefreshTokenDays(int refreshTokenDays) { this.refreshTokenDays = refreshTokenDays; }
    public int getLoginAuditDays() { return loginAuditDays; }
    public void setLoginAuditDays(int loginAuditDays) { this.loginAuditDays = loginAuditDays; }
    public int getOperationAuditDays() { return operationAuditDays; }
    public void setOperationAuditDays(int operationAuditDays) { this.operationAuditDays = operationAuditDays; }
    public int getSystemSettingLogDays() { return systemSettingLogDays; }
    public void setSystemSettingLogDays(int systemSettingLogDays) { this.systemSettingLogDays = systemSettingLogDays; }
    public int getNotificationLogDays() { return notificationLogDays; }
    public void setNotificationLogDays(int notificationLogDays) { this.notificationLogDays = notificationLogDays; }

    public int normalizedBatchSize() { return Math.max(1, Math.min(batchSize, 5_000)); }
    public int normalizedMaxBatchesPerRun() { return Math.max(1, Math.min(maxBatchesPerRun, 100)); }
}
