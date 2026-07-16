package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
class NotificationDeliveryRecoveryCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryRecoveryCompensator.class);

    private final NotificationEventMapper eventMapper;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;
    private final NotificationDeliveryFailurePolicy failurePolicy;
    private final RabbitNotificationQueueProperties properties;
    private final Clock clock;

    @Autowired
    NotificationDeliveryRecoveryCompensator(
        NotificationEventMapper eventMapper,
        NotificationDeliveryEventStateUpdater eventStateUpdater,
        NotificationDeliveryFailurePolicy failurePolicy,
        RabbitNotificationQueueProperties properties
    ) {
        this(eventMapper, eventStateUpdater, failurePolicy, properties, Clock.systemDefaultZone());
    }

    NotificationDeliveryRecoveryCompensator(
        NotificationEventMapper eventMapper,
        NotificationDeliveryEventStateUpdater eventStateUpdater,
        NotificationDeliveryFailurePolicy failurePolicy,
        RabbitNotificationQueueProperties properties,
        Clock clock
    ) {
        this.eventMapper = eventMapper;
        this.eventStateUpdater = eventStateUpdater;
        this.failurePolicy = failurePolicy;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.rabbit.notification.delivery-recovery-interval-ms:60000}")
    public void recoverExpiredClaims() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiredBefore = now.minusNanos(claimLeaseNanos());
        List<NotificationEvent> expiredEvents = eventMapper.selectList(
            new LambdaQueryWrapper<NotificationEvent>()
                .eq(NotificationEvent::getStatus, NotificationEventStatus.DELIVERING.code())
                .isNotNull(NotificationEvent::getDeliveryClaimedAt)
                .le(NotificationEvent::getDeliveryClaimedAt, expiredBefore)
                .orderByAsc(NotificationEvent::getDeliveryClaimedAt)
                .last("limit " + recoveryBatchSize())
        );
        for (NotificationEvent event : expiredEvents) {
            NotificationDeliveryFailureDecision decision = failurePolicy.decide(event);
            if (eventStateUpdater.recoverExpired(event, decision)) {
                LOGGER.warn(
                    "Expired notification delivery claim recovered eventId={} claimedAt={} claimedBy={} result={} retryCount={}",
                    event.getId(),
                    event.getDeliveryClaimedAt(),
                    event.getDeliveryClaimedBy(),
                    decision.status(),
                    decision.retryCount()
                );
            }
        }
    }

    private long claimLeaseNanos() {
        long leaseMillis = Math.max(1000L, properties.getDeliveryClaimLeaseMs());
        return Math.multiplyExact(leaseMillis, 1_000_000L);
    }

    private int recoveryBatchSize() {
        return Math.max(1, Math.min(properties.getDeliveryRecoveryBatchSize(), 1000));
    }
}
