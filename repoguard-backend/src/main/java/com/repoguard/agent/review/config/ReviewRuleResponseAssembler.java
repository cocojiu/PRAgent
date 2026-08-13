package com.repoguard.agent.review.config;

import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.review.ReviewRuleRegistry;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleResponseAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewRuleRegistry reviewRuleRegistry;
    private final ReviewRuleLifecycleGate lifecycleGate;

    public ReviewRuleResponseAssembler(
        ReviewRuleRegistry reviewRuleRegistry,
        ReviewRuleLifecycleGate lifecycleGate
    ) {
        this.reviewRuleRegistry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry");
        this.lifecycleGate = Objects.requireNonNull(lifecycleGate, "lifecycleGate");
    }

    ReviewRuleConfigDto toRuleDto(
        ReviewRuleConfig rule,
        long hitCount,
        ReviewQualityBaseline baseline
    ) {
        long configVersion = positiveVersion(rule.getConfigVersion());
        List<ReviewQualityGroupBaseline> qualityGroups = baseline == null ? List.of() : baseline.groups();
        String detectorVersion = reviewRuleRegistry.detectorVersion(rule.getId());
        return new ReviewRuleConfigDto(
            rule.getId(),
            rule.getRuleName(),
            rule.getScope(),
            defaultString(rule.getApplicableLanguages()),
            defaultString(rule.getFilePatterns()),
            lower(rule.getSeverity()),
            lower(rule.getStatus()),
            hitCount,
            (rule.getConfidence() == null ? 0 : rule.getConfidence()) + "%",
            format(rule.getUpdatedAt()),
            rule.getDescription(),
            defaultString(rule.getPositiveExample()),
            defaultString(rule.getFalsePositiveGuidance()),
            lower(rule.getEnforcementMode()),
            detectorVersion,
            configVersion,
            positiveVersion(rule.getPolicyVersion()),
            lifecycleGate.evaluate(rule.getId(), detectorVersion, configVersion, qualityGroups)
        );
    }

    ReviewRulePolicyVersionDto toVersionDto(
        ReviewRulePolicySnapshot snapshot,
        long activePolicyVersion
    ) {
        return new ReviewRulePolicyVersionDto(
            positiveVersion(snapshot.getPolicyVersion()),
            positiveVersion(snapshot.getConfigVersion()),
            snapshot.getDetectorVersion(),
            lower(snapshot.getSeverity()),
            lower(snapshot.getStatus()),
            (snapshot.getConfidence() == null ? 0 : snapshot.getConfidence()) + "%",
            lower(snapshot.getEnforcementMode()),
            snapshot.getChangeType(),
            snapshot.getSourcePolicyVersion(),
            format(snapshot.getCreatedAt()),
            positiveVersion(snapshot.getPolicyVersion()) == activePolicyVersion
        );
    }

    long positiveVersion(Long value) {
        return value == null || value < 1 ? 1 : value;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
