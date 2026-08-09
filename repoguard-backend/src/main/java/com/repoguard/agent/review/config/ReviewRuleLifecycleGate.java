package com.repoguard.agent.review.config;

import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.review.quality.ReviewQualityGatePolicy;
import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleLifecycleGate {

    private final ReviewQualityGatePolicy qualityGatePolicy;

    public ReviewRuleLifecycleGate() {
        this(new ReviewQualityGatePolicy());
    }

    @Autowired
    ReviewRuleLifecycleGate(ReviewQualityGatePolicy qualityGatePolicy) {
        this.qualityGatePolicy = Objects.requireNonNull(qualityGatePolicy, "qualityGatePolicy");
    }

    public ReviewRuleQualityGateDto evaluate(
        String ruleId,
        long configVersion,
        List<ReviewQualityGroupBaseline> groups
    ) {
        return evaluate(ruleId, null, configVersion, groups);
    }

    public ReviewRuleQualityGateDto evaluate(
        String ruleId,
        String detectorVersion,
        long configVersion,
        List<ReviewQualityGroupBaseline> groups
    ) {
        List<ReviewQualityGroupBaseline> matching = groups == null
            ? List.of()
            : groups.stream()
                .filter(group -> group.ruleConfigVersion() == configVersion)
                .filter(group -> containsComponent(group.ruleId(), ruleId))
                .filter(group -> detectorVersion == null || containsComponent(group.detectorVersion(), detectorVersion))
                .toList();
        return qualityGatePolicy.evaluate(matching);
    }

    private boolean containsComponent(String compositeValue, String expectedValue) {
        if (compositeValue == null || expectedValue == null) {
            return false;
        }
        String expected = expectedValue.trim().toUpperCase(Locale.ROOT);
        for (String part : compositeValue.toUpperCase(Locale.ROOT).split("[+/]")) {
            if (expected.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }
}
