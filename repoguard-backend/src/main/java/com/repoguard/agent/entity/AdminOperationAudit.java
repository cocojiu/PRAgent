package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("admin_operation_audit")
public class AdminOperationAudit {

    @TableId
    private Long id;
    private Long actorUserId;
    private String actorUsername;
    private String action;
    private String targetType;
    private String targetId;
    private String diffJson;
    private String traceId;
    private String clientIp;
    private String userAgent;
    private String result;
    private String failureCategory;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getDiffJson() { return diffJson; }
    public void setDiffJson(String diffJson) { this.diffJson = diffJson; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
