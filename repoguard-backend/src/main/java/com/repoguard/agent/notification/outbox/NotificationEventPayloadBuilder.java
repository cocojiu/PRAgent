package com.repoguard.agent.notification.outbox;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationMessage;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricSnapshot;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventPayloadBuilder {

    private final NotificationEventKeyFactory eventKeyFactory;
    private final NotificationMessageJsonSerializer messageJsonSerializer;

    public NotificationEventPayloadBuilder(
        NotificationEventKeyFactory eventKeyFactory,
        NotificationMessageJsonSerializer messageJsonSerializer
    ) {
        this.eventKeyFactory = Objects.requireNonNull(eventKeyFactory, "eventKeyFactory");
        this.messageJsonSerializer = Objects.requireNonNull(messageJsonSerializer, "messageJsonSerializer");
    }

    public NotificationEventPayload build(
        String eventType,
        ReviewTask task,
        Long batchId,
        int findingCount,
        int commentSucceededCount,
        int commentFailedCount,
        int commentSkippedCount
    ) {
        NotificationMessage message = new NotificationMessage(
            eventType,
            task.getId(),
            batchId,
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getTitle(),
            task.getStatus(),
            task.getRiskLevel(),
            findingCount,
            commentSucceededCount,
            commentFailedCount,
            commentSkippedCount,
            "/repoguard/tasks/" + task.getId()
        );
        return new NotificationEventPayload(
            eventKeyFactory.create(eventType, task.getId(), batchId),
            message,
            messageJsonSerializer.serialize(message)
        );
    }

    public NotificationEventPayload buildReleaseAlert(LlmModelReleaseMetricSnapshot snapshot) {
        String summary = "版本 " + snapshot.releaseKey() + "（" + snapshot.provider() + "/" + snapshot.modelName()
            + "）触发 " + String.join("、", snapshot.alertCodes()) + "；阈值动作：" + snapshot.action()
            + "；样本 " + snapshot.sampleCount() + "；窗口 " + snapshot.windowStart() + " ~ " + snapshot.windowEnd();
        NotificationMessage message = new NotificationMessage(
            "MODEL_RELEASE_ALERT", null, null, "*", "*", null, "LLM 模型发布运行告警",
            snapshot.alertState(), "HIGH", snapshot.sampleCount().intValue(), 0, 0, 0,
            "/repoguard/config/review-calibration/release-center", summary
        );
        return new NotificationEventPayload(
            eventKeyFactory.createReleaseAlert(snapshot.releaseKey(), snapshot.windowStart().toString(), snapshot.alertFingerprint()),
            message,
            messageJsonSerializer.serialize(message)
        );
    }
}
