package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.ReviewCalibrationSampleDto;
import com.repoguard.agent.dto.ReviewCalibrationVersionDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewCalibrationQueueMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Sample;
import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Summary;
import com.repoguard.agent.review.ReviewRuleRegistry;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.ReviewStrategyReleaseProvider;
import com.repoguard.agent.review.config.ReviewRuleConfigPolicy;
import com.repoguard.agent.review.config.ReviewRuleLifecycleGate;
import com.repoguard.agent.service.ReviewCalibrationService;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ReviewCalibrationQueueService implements ReviewCalibrationService {

    private static final long TARGET_LABELED_SAMPLES = 30;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewRuleConfigMapper ruleConfigMapper;
    private final ReviewCalibrationQueueMapper queueMapper;
    private final ReviewRuleRegistry ruleRegistry;
    private final ReviewRuleConfigPolicy ruleConfigPolicy;
    private final ReviewStrategyReleaseProvider strategyReleaseProvider;
    private final ReviewRuleLifecycleGate lifecycleGate;

    public ReviewCalibrationQueueService(
        ReviewRuleConfigMapper ruleConfigMapper,
        ReviewCalibrationQueueMapper queueMapper,
        ReviewRuleRegistry ruleRegistry,
        ReviewRuleConfigPolicy ruleConfigPolicy,
        ReviewStrategyReleaseProvider strategyReleaseProvider,
        ReviewRuleLifecycleGate lifecycleGate
    ) {
        this.ruleConfigMapper = Objects.requireNonNull(ruleConfigMapper, "ruleConfigMapper");
        this.queueMapper = Objects.requireNonNull(queueMapper, "queueMapper");
        this.ruleRegistry = Objects.requireNonNull(ruleRegistry, "ruleRegistry");
        this.ruleConfigPolicy = Objects.requireNonNull(ruleConfigPolicy, "ruleConfigPolicy");
        this.strategyReleaseProvider = Objects.requireNonNull(strategyReleaseProvider, "strategyReleaseProvider");
        this.lifecycleGate = Objects.requireNonNull(lifecycleGate, "lifecycleGate");
    }

    @Override
    public ReviewCalibrationQueueDto getQueue(String ruleId, int limit, boolean includeIgnored) {
        String normalizedRuleId = ruleConfigPolicy.normalizeRuleId(ruleId);
        ensureRegistered(normalizedRuleId);
        ReviewRuleConfig rule = loadRule(normalizedRuleId);
        String detectorVersion = ruleRegistry.detectorVersion(normalizedRuleId);
        long configVersion = positiveVersion(rule.getConfigVersion());
        ReviewStrategyRelease release = strategyReleaseProvider.getActiveRelease();
        String versionKey = versionKey(normalizedRuleId, detectorVersion, configVersion, release);
        Summary summary = queueMapper.selectVersionSummary(
            normalizedRuleId,
            detectorVersion,
            configVersion,
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.verifierVersion(),
            release.aggregationVersion()
        );
        long total = count(summary == null ? null : summary.totalFindings());
        long labeled = count(summary == null ? null : summary.labeledCount());
        long confirmed = count(summary == null ? null : summary.confirmedValidCount());
        long falsePositives = count(summary == null ? null : summary.falsePositiveCount());
        long pending = count(summary == null ? null : summary.pendingCount());
        ReviewQualityGroupBaseline qualityGroup = qualityGroup(
            normalizedRuleId,
            detectorVersion,
            configVersion,
            release,
            versionKey,
            total,
            labeled,
            confirmed,
            falsePositives,
            pending,
            count(summary == null ? null : summary.anchoredCount()),
            count(summary == null ? null : summary.duplicateCount())
        );
        ReviewRuleQualityGateDto qualityGate = lifecycleGate.evaluate(
            normalizedRuleId,
            detectorVersion,
            configVersion,
            List.of(qualityGroup)
        );
        List<ReviewCalibrationSampleDto> samples = queueMapper.selectPendingSamples(
            normalizedRuleId,
            detectorVersion,
            configVersion,
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.verifierVersion(),
            release.aggregationVersion(),
            includeIgnored,
            limit
        ).stream().map(sample -> toDto(sample, versionKey)).toList();

        return new ReviewCalibrationQueueDto(
            new ReviewCalibrationVersionDto(
                normalizedRuleId,
                safeText(rule.getRuleName(), normalizedRuleId),
                detectorVersion,
                configVersion,
                positiveVersion(rule.getPolicyVersion()),
                release.snapshotId(),
                release.strategyVersion(),
                release.promptVersion(),
                release.contextVersion(),
                release.schemaVersion(),
                release.verifierVersion(),
                release.aggregationVersion(),
                normalizeMode(rule.getEnforcementMode()),
                release.enforcementMode().name().toLowerCase(Locale.ROOT),
                release.replayVerified(),
                versionKey
            ),
            TARGET_LABELED_SAMPLES,
            total,
            labeled,
            confirmed,
            falsePositives,
            pending,
            Math.max(0L, TARGET_LABELED_SAMPLES - labeled),
            qualityGate,
            samples
        );
    }

    private ReviewQualityGroupBaseline qualityGroup(
        String ruleId,
        String detectorVersion,
        long configVersion,
        ReviewStrategyRelease release,
        String versionKey,
        long total,
        long labeled,
        long confirmed,
        long falsePositives,
        long pending,
        long anchored,
        long duplicates
    ) {
        return new ReviewQualityGroupBaseline(
            ruleId,
            "CALIBRATION",
            "ALL",
            "ALL",
            "HIGH",
            versionKey,
            detectorVersion,
            configVersion,
            positiveVersion(release.snapshotId()),
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.verifierVersion(),
            release.aggregationVersion(),
            total,
            labeled,
            BigDecimal.ZERO,
            confirmed,
            falsePositives,
            pending,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            total,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            anchored,
            BigDecimal.ZERO,
            duplicates,
            BigDecimal.ZERO,
            "INSUFFICIENT_SAMPLE",
            List.of()
        );
    }

    private ReviewCalibrationSampleDto toDto(Sample sample, String versionKey) {
        return new ReviewCalibrationSampleDto(
            value(sample.findingId(), 0L),
            value(sample.taskId(), 0L),
            sample.prNumber(),
            safeText(sample.title(), ""),
            safeText(sample.repository(), "UNKNOWN"),
            safeText(sample.organization(), "UNKNOWN"),
            safeText(sample.commitSha(), ""),
            safeText(sample.prUrl(), ""),
            sample.taskCreatedAt() == null ? "" : DATE_TIME_FORMATTER.format(sample.taskCreatedAt()),
            safeText(sample.source(), "UNKNOWN"),
            safeText(sample.ruleId(), "UNASSIGNED"),
            safeText(sample.severity(), "INFO"),
            safeText(sample.confidence(), "LOW"),
            safeText(sample.filePath(), ""),
            sample.lineNumber(),
            safeText(sample.message(), ""),
            safeText(sample.evidence(), ""),
            safeText(sample.impact(), ""),
            safeText(sample.recommendation(), ""),
            safeText(sample.preconditions(), ""),
            safeText(sample.issueType(), "GENERAL"),
            safeText(sample.verificationStatus(), "NOT_REQUIRED"),
            Boolean.TRUE.equals(sample.blockingCandidate()),
            normalizeMode(sample.enforcementMode()),
            safeText(sample.feedbackStatus(), "UNREVIEWED"),
            versionKey
        );
    }

    private String versionKey(
        String ruleId,
        String detectorVersion,
        long configVersion,
        ReviewStrategyRelease release
    ) {
        return ruleId
            + "|detector=" + detectorVersion
            + "|config=" + configVersion
            + "|prompt=" + release.promptVersion()
            + "|context=" + release.contextVersion()
            + "|schema=" + release.schemaVersion()
            + "|verifier=" + release.verifierVersion()
            + "|aggregation=" + release.aggregationVersion();
    }

    private ReviewRuleConfig loadRule(String ruleId) {
        ReviewRuleConfig rule = ruleConfigMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule not found: " + ruleId);
        }
        return rule;
    }

    private void ensureRegistered(String ruleId) {
        if (!ruleRegistry.contains(ruleId)) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review rule has no registered detector implementation: " + ruleId
            );
        }
    }

    private long positiveVersion(Long value) {
        return value == null || value < 1 ? 1L : value;
    }

    private long positiveVersion(long value) {
        return value < 1 ? 1L : value;
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeMode(String value) {
        return safeText(value, "OBSERVE").toLowerCase(Locale.ROOT);
    }
}
