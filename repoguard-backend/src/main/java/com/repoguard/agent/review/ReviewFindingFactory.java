package com.repoguard.agent.review;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingFactory {

    ReviewFindingResult finding(EffectiveFinding effectiveFinding) {
        EffectiveFinding effective = Objects.requireNonNull(effectiveFinding, "effectiveFinding");
        RuleMatch match = effective.match();
        return new ReviewFindingResult(
            effective.severity(),
            "RULE",
            match.ruleId(),
            match.filePath(),
            match.lineNumber(),
            match.message(),
            match.recommendation(),
            effective.confidence(),
            match.evidence(),
            match.impact(),
            match.recommendation(),
            effective.blocking(),
            match.reviewDimension(),
            effective.enforcementMode().name(),
            effective.policyReason()
        );
    }
}
