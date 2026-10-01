package com.repoguard.agent.retention;

import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.dto.OperationalPayloadRetentionPreview;
import com.repoguard.agent.mapper.OperationalDataRetentionMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OperationalPayloadRetentionQuery {
    private final OperationalDataRetentionMapper mapper;
    private final OperationalDataRetentionProperties properties;

    public OperationalPayloadRetentionQuery(OperationalDataRetentionMapper mapper, OperationalDataRetentionProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional(readOnly = true, timeout = 5)
    public List<OperationalPayloadRetentionPreview> preview() {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LocalDateTime now = LocalDateTime.now();
        int feedbackDays = properties.normalizedFeedbackPayloadDays();
        int runDays = properties.normalizedEvaluationRunPayloadDays();
        var feedback = mapper.previewFeedbackPayload(tenantId, now.minusDays(feedbackDays));
        var runs = mapper.previewEvaluationRunPayload(tenantId, now.minusDays(runDays));
        return List.of(view("github_feedback_event", properties.isFeedbackPayloadPurgeEnabled(), feedbackDays, now, feedback),
            view("llm_evaluation_run", properties.isEvaluationRunPayloadPurgeEnabled(), runDays, now, runs));
    }

    private OperationalPayloadRetentionPreview view(String table, boolean enabled, int days, LocalDateTime now,
        OperationalDataRetentionMapper.PayloadPreview value) {
        return new OperationalPayloadRetentionPreview(table, properties.isEnabled() && enabled, days, now.minusDays(days),
            value.candidateCount(), value.oldestAt(), value.payloadBytes());
    }
}
