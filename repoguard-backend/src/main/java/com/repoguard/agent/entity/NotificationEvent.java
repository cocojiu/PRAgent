package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("notification_event")
public class NotificationEvent {

    @TableId
    private Long id;
    private String eventKey;
    private String eventType;
    private Long taskId;
    private Long batchId;
    private String payload;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime publishClaimedAt;
    private String publishClaimedBy;
    private LocalDateTime deliveryClaimedAt;
    private String deliveryClaimedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getPublishClaimedAt() { return publishClaimedAt; }
    public void setPublishClaimedAt(LocalDateTime publishClaimedAt) { this.publishClaimedAt = publishClaimedAt; }
    public String getPublishClaimedBy() { return publishClaimedBy; }
    public void setPublishClaimedBy(String publishClaimedBy) { this.publishClaimedBy = publishClaimedBy; }
    public LocalDateTime getDeliveryClaimedAt() { return deliveryClaimedAt; }
    public void setDeliveryClaimedAt(LocalDateTime deliveryClaimedAt) { this.deliveryClaimedAt = deliveryClaimedAt; }
    public String getDeliveryClaimedBy() { return deliveryClaimedBy; }
    public void setDeliveryClaimedBy(String deliveryClaimedBy) { this.deliveryClaimedBy = deliveryClaimedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
