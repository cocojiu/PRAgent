package com.repoguard.agent.review.config;

import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.quality.ReviewQualityGatePolicy;
import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReviewStrategyLifecycleGate {

    private final ReviewQualityGatePolicy qualityGatePolicy;

    public ReviewStrategyLifecycleGate() {
        this(new ReviewQualityGatePolicy());
    }

    @Autowired
    ReviewStrategyLifecycleGate(ReviewQualityGatePolicy qualityGatePolicy) {
        this.qualityGatePolicy = Objects.requireNonNull(qualityGatePolicy, "qualityGatePolicy");
    }

    public ReviewRuleQualityGateDto evaluate(
        ReviewStrategyRelease release,
        List<ReviewQualityGroupBaseline> groups
    ) {
        List<ReviewQualityGroupBaseline> matching = groups == null
            ? List.of()
            : groups.stream()
                .filter(group -> containsLlm(group.source()))
                .filter(group -> release.promptVersion().equals(group.promptVersion()))
                .filter(group -> release.contextVersion().equals(group.contextVersion()))
                .filter(group -> release.schemaVersion().equals(group.schemaVersion()))
                .filter(group -> release.verifierVersion().equals(group.verifierVersion()))
                .filter(group -> release.aggregationVersion().equals(group.aggregationVersion()))
                .toList();
        return qualityGatePolicy.evaluate(matching);
    }

    private boolean containsLlm(String source) {
        return source != null && source.toUpperCase(Locale.ROOT).contains("LLM");
    }
}
