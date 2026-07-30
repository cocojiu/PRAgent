package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewCalibrationQueueMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Sample;
import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Summary;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.LlmReviewVersions;
import com.repoguard.agent.review.ReviewRuleRegistry;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.ReviewStrategyReleaseProvider;
import com.repoguard.agent.review.ServerRiskAggregator;
import com.repoguard.agent.review.config.ReviewRuleConfigPolicy;
import com.repoguard.agent.review.config.ReviewRuleLifecycleGate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReviewCalibrationQueueServiceTest {

    private final ReviewRuleConfigMapper ruleConfigMapper = Mockito.mock(ReviewRuleConfigMapper.class);
    private final ReviewCalibrationQueueMapper queueMapper = Mockito.mock(ReviewCalibrationQueueMapper.class);
    private final ReviewRuleRegistry ruleRegistry = Mockito.mock(ReviewRuleRegistry.class);
    private final ReviewStrategyReleaseProvider releaseProvider = Mockito.mock(ReviewStrategyReleaseProvider.class);
    private final ReviewCalibrationQueueService service = new ReviewCalibrationQueueService(
        ruleConfigMapper,
        queueMapper,
        ruleRegistry,
        new ReviewRuleConfigPolicy(),
        releaseProvider,
        new ReviewRuleLifecycleGate()
    );

    @Test
    void buildsCurrentVersionQueueFromPinnedQualitySummary() {
        ReviewRuleConfig rule = rule();
        ReviewStrategyRelease release = release();
        when(ruleRegistry.contains("RG-AUTH-001")).thenReturn(true);
        when(ruleRegistry.detectorVersion("RG-AUTH-001")).thenReturn("rg-auth-001-detector-v2");
        when(ruleConfigMapper.selectById("RG-AUTH-001")).thenReturn(rule);
        when(releaseProvider.getActiveRelease()).thenReturn(release);
        when(queueMapper.selectVersionSummary(
            "RG-AUTH-001",
            "rg-auth-001-detector-v2",
            4,
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.verifierVersion(),
            release.aggregationVersion()
        )).thenReturn(new Summary(20L, 20L, 18L, 2L, 0L, 20L, 0L));
        when(queueMapper.selectPendingSamples(
            "RG-AUTH-001",
            "rg-auth-001-detector-v2",
            4,
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.verifierVersion(),
            release.aggregationVersion(),
            false,
            30
        )).thenReturn(List.of(sample()));

        var queue = service.getQueue(" rg-auth-001 ", 30, false);

        assertThat(queue.version().ruleId()).isEqualTo("RG-AUTH-001");
        assertThat(queue.version().ruleConfigVersion()).isEqualTo(4);
        assertThat(queue.version().strategySnapshotId()).isEqualTo(8);
        assertThat(queue.version().ruleEnforcementMode()).isEqualTo("observe");
        assertThat(queue.version().strategyEnforcementMode()).isEqualTo("observe");
        assertThat(queue.totalHighRiskFindings()).isEqualTo(20);
        assertThat(queue.labeledHighRiskSamples()).isEqualTo(20);
        assertThat(queue.confirmedValidSamples()).isEqualTo(18);
        assertThat(queue.falsePositiveSamples()).isEqualTo(2);
        assertThat(queue.remainingToTarget()).isEqualTo(10);
        assertThat(queue.qualityGate().status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(queue.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.findingId()).isEqualTo(91);
            assertThat(sample.taskId()).isEqualTo(51);
            assertThat(sample.taskCreatedAt()).isEqualTo("2026-07-31 09:30:00");
            assertThat(sample.versionKey()).isEqualTo(queue.version().versionKey());
        });
        verify(queueMapper).selectPendingSamples(
            "RG-AUTH-001",
            "rg-auth-001-detector-v2",
            4,
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.verifierVersion(),
            release.aggregationVersion(),
            false,
            30
        );
        verify(queueMapper).selectVersionSummary(
            "RG-AUTH-001",
            "rg-auth-001-detector-v2",
            4,
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.verifierVersion(),
            release.aggregationVersion()
        );
    }

    @Test
    void rejectsRulesWithoutRegisteredDetector() {
        when(ruleRegistry.contains("RG-NOT-REGISTERED")).thenReturn(false);

        assertThatThrownBy(() -> service.getQueue("rg-not-registered", 30, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no registered detector");
    }

    private ReviewRuleConfig rule() {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setId("RG-AUTH-001");
        rule.setRuleName("权限保护缺失");
        rule.setConfigVersion(4L);
        rule.setPolicyVersion(7L);
        rule.setEnforcementMode("OBSERVE");
        return rule;
    }

    private ReviewStrategyRelease release() {
        return new ReviewStrategyRelease(
            8,
            3,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION,
            EnforcementMode.OBSERVE,
            true
        );
    }

    private Sample sample() {
        return new Sample(
            91L,
            51L,
            132,
            "Guard admin endpoint",
            "demo",
            "octocat",
            "abc123",
            "https://github.com/octocat/demo/pull/132",
            LocalDateTime.of(2026, 7, 31, 9, 30),
            "LLM+RULE",
            "LLM / RG-AUTH-001",
            "HIGH",
            "HIGH",
            "src/main/java/com/demo/AdminController.java",
            42,
            "管理接口缺少权限保护",
            "新增接口没有权限注解",
            "未授权用户可调用",
            "增加管理员权限注解",
            "接口可从公网调用",
            "AUTHORIZATION",
            "VERIFIED",
            true,
            "OBSERVE",
            "UNREVIEWED",
            "llm-review-v2+rg-auth-001-detector-v2",
            4L,
            8L,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION
        );
    }
}
