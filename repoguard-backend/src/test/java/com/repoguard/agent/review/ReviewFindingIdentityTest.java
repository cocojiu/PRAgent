package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewFinding;
import org.junit.jupiter.api.Test;

class ReviewFindingIdentityTest {

    @Test
    void ignoresLineNumbersButKeepsFileAndRuleIdentity() {
        ReviewFinding first = finding("src\\App.java", 10, "Use logger on line 10");
        ReviewFinding shifted = finding("./src/App.java", 42, "Use logger on line 42");
        ReviewFinding otherFile = finding("src/Other.java", 10, "Use logger on line 10");

        assertThat(ReviewFindingIdentity.fingerprint(42L, first))
            .isEqualTo(ReviewFindingIdentity.fingerprint(42L, shifted));
        assertThat(ReviewFindingIdentity.fingerprint(42L, first))
            .isNotEqualTo(ReviewFindingIdentity.fingerprint(42L, otherFile));
        assertThat(ReviewFindingIdentity.locationIndependentKey(42L, first))
            .isEqualTo(ReviewFindingIdentity.locationIndependentKey(42L, otherFile));
    }

    @Test
    void normalizesSemanticSummaryAndNullValues() {
        assertThat(ReviewFindingIdentity.semanticSummary("  A  finding\n at line: 12  "))
            .isEqualTo("a finding at line");
        assertThat(ReviewFindingIdentity.semanticSummary(null)).isEmpty();
        assertThat(ReviewFindingIdentity.normalizePath(" ./src\\App.java "))
            .isEqualTo("src/app.java");
        assertThat(ReviewFindingIdentity.fingerprint(1L, null)).isNull();
    }

    private ReviewFinding finding(String path, int line, String message) {
        ReviewFinding finding = new ReviewFinding();
        finding.setSource("RULE");
        finding.setRuleId("RG-001");
        finding.setIssueType("OBSERVABILITY");
        finding.setFilePath(path);
        finding.setLineNumber(line);
        finding.setMessage(message);
        finding.setAnchorType("ADDED_LINE");
        return finding;
    }
}
