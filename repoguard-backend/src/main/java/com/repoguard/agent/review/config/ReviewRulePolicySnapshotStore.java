package com.repoguard.agent.review.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.mapper.ReviewRulePolicySnapshotMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewRulePolicySnapshotStore {

    private final ReviewRulePolicySnapshotMapper snapshotMapper;

    public ReviewRulePolicySnapshotStore(ReviewRulePolicySnapshotMapper snapshotMapper) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
    }

    public ReviewRulePolicySnapshot save(ReviewRuleConfig rule, String changeType, Long sourcePolicyVersion) {
        ReviewRulePolicySnapshot snapshot = new ReviewRulePolicySnapshot();
        snapshot.setRuleId(rule.getId());
        snapshot.setPolicyVersion(rule.getPolicyVersion());
        snapshot.setConfigVersion(rule.getConfigVersion());
        snapshot.setDetectorVersion(rule.getDetectorVersion());
        snapshot.setDetectorType(defaultString(rule.getDetectorType(), "BUILTIN"));
        snapshot.setMatcherExpression(defaultString(rule.getMatcherExpression()));
        snapshot.setExceptionPatterns(defaultString(rule.getExceptionPatterns()));
        snapshot.setRuleName(rule.getRuleName());
        snapshot.setScope(rule.getScope());
        snapshot.setApplicableLanguages(defaultString(rule.getApplicableLanguages()));
        snapshot.setFilePatterns(defaultString(rule.getFilePatterns()));
        snapshot.setSeverity(rule.getSeverity());
        snapshot.setStatus(rule.getStatus());
        snapshot.setConfidence(rule.getConfidence());
        snapshot.setEnforcementMode(rule.getEnforcementMode());
        snapshot.setDescription(rule.getDescription());
        snapshot.setPositiveExample(defaultString(rule.getPositiveExample()));
        snapshot.setFalsePositiveGuidance(defaultString(rule.getFalsePositiveGuidance()));
        snapshot.setChangeType(changeType);
        snapshot.setSourcePolicyVersion(sourcePolicyVersion);
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    public List<ReviewRulePolicySnapshot> page(String ruleId, Long cursor, int limit) {
        LambdaQueryWrapper<ReviewRulePolicySnapshot> query = new LambdaQueryWrapper<ReviewRulePolicySnapshot>()
            .eq(ReviewRulePolicySnapshot::getRuleId, ruleId)
            .orderByDesc(ReviewRulePolicySnapshot::getPolicyVersion)
            .orderByDesc(ReviewRulePolicySnapshot::getId);
        if (cursor != null) {
            query.lt(ReviewRulePolicySnapshot::getPolicyVersion, cursor);
        }
        return snapshotMapper.selectList(query.last("limit " + limit));
    }

    public long count(String ruleId) {
        return snapshotMapper.selectCount(
            new LambdaQueryWrapper<ReviewRulePolicySnapshot>().eq(ReviewRulePolicySnapshot::getRuleId, ruleId)
        );
    }

    public ReviewRulePolicySnapshot find(String ruleId, long policyVersion) {
        return snapshotMapper.selectOne(
            new LambdaQueryWrapper<ReviewRulePolicySnapshot>()
                .eq(ReviewRulePolicySnapshot::getRuleId, ruleId)
                .eq(ReviewRulePolicySnapshot::getPolicyVersion, policyVersion)
                .last("limit 1")
        );
    }

    public void restore(ReviewRuleConfig rule, ReviewRulePolicySnapshot snapshot, long newPolicyVersion) {
        rule.setDetectorVersion(snapshot.getDetectorVersion());
        rule.setDetectorType(defaultString(snapshot.getDetectorType(), "BUILTIN"));
        rule.setMatcherExpression(defaultString(snapshot.getMatcherExpression()));
        rule.setExceptionPatterns(defaultString(snapshot.getExceptionPatterns()));
        rule.setConfigVersion(snapshot.getConfigVersion());
        rule.setPolicyVersion(newPolicyVersion);
        rule.setRuleName(snapshot.getRuleName());
        rule.setScope(snapshot.getScope());
        rule.setApplicableLanguages(snapshot.getApplicableLanguages());
        rule.setFilePatterns(snapshot.getFilePatterns());
        rule.setSeverity(snapshot.getSeverity());
        rule.setStatus(snapshot.getStatus());
        rule.setConfidence(snapshot.getConfidence());
        rule.setEnforcementMode(snapshot.getEnforcementMode());
        rule.setDescription(snapshot.getDescription());
        rule.setPositiveExample(snapshot.getPositiveExample());
        rule.setFalsePositiveGuidance(snapshot.getFalsePositiveGuidance());
        rule.setUpdatedAt(LocalDateTime.now());
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
