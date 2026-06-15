package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorker {

    private final NotificationEventMapper eventMapper;
    private final NotificationChannelBindingMapper bindingMapper;
    private final NotificationDeliveryLogMapper deliveryLogMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;

    public NotificationDeliveryWorker(
        NotificationEventMapper eventMapper,
        NotificationChannelBindingMapper bindingMapper,
        NotificationDeliveryLogMapper deliveryLogMapper,
        NotificationChannelAdapterRegistry adapterRegistry,
        ObjectMapper objectMapper
    ) {
        this.eventMapper = eventMapper;
        this.bindingMapper = bindingMapper;
        this.deliveryLogMapper = deliveryLogMapper;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${app.rabbit.notification.queue}", concurrency = "${app.rabbit.notification.worker-concurrency:1}")
    public void handle(
        NotificationEventMessage message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        try {
            deliver(message.eventId());
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException ex) {
            channel.basicReject(deliveryTag, false);
        }
    }

    void deliver(Long eventId) {
        NotificationEvent event = eventMapper.selectById(eventId);
        if (event == null || "DELIVERED".equalsIgnoreCase(event.getStatus()) || "DEAD".equalsIgnoreCase(event.getStatus())) {
            return;
        }
        NotificationMessage message = parseMessage(event);
        eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .ne("status", "DELIVERED")
                .set("status", "DELIVERING")
                .set("updated_at", LocalDateTime.now())
        );

        List<NotificationChannelBinding> bindings = loadBindings(message);
        boolean anyFailed = false;
        for (NotificationChannelBinding binding : bindings) {
            if (!supports(binding, event.getEventType()) || alreadyDelivered(event.getId(), binding.getId())) {
                continue;
            }
            NotificationSendResult result = adapterRegistry.get(binding.getProvider()).send(message, binding);
            saveLog(event, binding, result);
            if (!result.success()) {
                anyFailed = true;
            }
        }

        if (anyFailed) {
            int retryCount = safe(event.getRetryCount()) + 1;
            boolean dead = retryCount >= 5;
            eventMapper.update(
                new UpdateWrapper<NotificationEvent>()
                    .eq("id", event.getId())
                    .set("status", dead ? "DEAD" : "DELIVERY_FAILED")
                    .set("retry_count", retryCount)
                    .set("next_retry_at", dead ? null : LocalDateTime.now().plusMinutes(retryMinutes(retryCount)))
                    .set("last_error", "One or more notification bindings failed")
                    .set("updated_at", LocalDateTime.now())
            );
        } else {
            eventMapper.update(
                new UpdateWrapper<NotificationEvent>()
                    .eq("id", event.getId())
                    .set("status", "DELIVERED")
                    .set("next_retry_at", null)
                    .set("last_error", null)
                    .set("updated_at", LocalDateTime.now())
            );
        }
    }

    private List<NotificationChannelBinding> loadBindings(NotificationMessage message) {
        return bindingMapper.selectList(
            new LambdaQueryWrapper<NotificationChannelBinding>()
                .eq(NotificationChannelBinding::getEnabled, true)
                .eq(NotificationChannelBinding::getOrganization, message.organization())
                .eq(NotificationChannelBinding::getRepository, message.repository())
        );
    }

    private boolean supports(NotificationChannelBinding binding, String eventType) {
        return switch (eventType) {
            case "REVIEW_COMPLETED" -> Boolean.TRUE.equals(binding.getNotifyReviewCompleted());
            case "REVIEW_FAILED" -> Boolean.TRUE.equals(binding.getNotifyReviewFailed());
            case "HUMAN_REVIEW_REQUIRED" -> Boolean.TRUE.equals(binding.getNotifyHumanReviewRequired());
            case "GITHUB_COMMENT_PUBLISHED" -> Boolean.TRUE.equals(binding.getNotifyGithubComment());
            default -> false;
        };
    }

    private boolean alreadyDelivered(Long eventId, Long bindingId) {
        return deliveryLogMapper.selectCount(
            new LambdaQueryWrapper<NotificationDeliveryLog>()
                .eq(NotificationDeliveryLog::getEventId, eventId)
                .eq(NotificationDeliveryLog::getBindingId, bindingId)
                .eq(NotificationDeliveryLog::getStatus, "SUCCESS")
        ) > 0;
    }

    private void saveLog(NotificationEvent event, NotificationChannelBinding binding, NotificationSendResult result) {
        NotificationDeliveryLog log = new NotificationDeliveryLog();
        log.setEventId(event.getId());
        log.setBindingId(binding.getId());
        log.setTaskId(event.getTaskId());
        log.setProvider(binding.getProvider());
        log.setStatus(result.success() ? "SUCCESS" : "FAILED");
        log.setAttemptCount(safe(event.getRetryCount()) + 1);
        log.setFailureReason(result.success() ? null : truncate(result.message(), 1024));
        log.setRequestId(result.requestId());
        log.setSentAt(LocalDateTime.now());
        log.setCreatedAt(LocalDateTime.now());
        deliveryLogMapper.insert(log);
    }

    private NotificationMessage parseMessage(NotificationEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), NotificationMessage.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Notification payload parse failed", ex);
        }
    }

    private int retryMinutes(int retryCount) {
        return switch (retryCount) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 15;
            case 4 -> 30;
            default -> 60;
        };
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
