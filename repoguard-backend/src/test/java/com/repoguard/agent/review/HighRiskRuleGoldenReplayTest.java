package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class HighRiskRuleGoldenReplayTest {

    private static final String GOLDEN_CASES = "review-quality/high-risk-rule-golden-cases.json";
    private static final Set<String> HIGH_RISK_RULE_IDS = Set.of(
        "RG-AUTH-001",
        "RG-SECRET-001",
        "RG-LOG-001",
        "RG-MQ-001",
        "RG-GH-001",
        "RG-DB-002",
        "RG-DB-003"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void goldenCatalogKeepsBalancedExplicitContractsForEveryHighRiskRule() throws IOException {
        List<GoldenCase> cases = loadCases();

        assertThat(cases).hasSize(140);
        assertThat(cases).extracting(GoldenCase::id).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(sample -> {
            assertThat(sample.id()).isNotBlank();
            assertThat(sample.ruleId()).isIn(HIGH_RISK_RULE_IDS);
            assertThat(sample.filePath()).isNotBlank();
            assertThat(sample.patch()).contains("@@").contains("\n+");
            assertThat(sample.rationale()).isNotBlank();
            assertContractIsComplete(
                sample.expectedFinding(),
                sample.expectedSeverity(),
                sample.expectedConfidence(),
                sample.expectedBlocking(),
                sample.expectedRisk()
            );
            assertContractIsComplete(
                sample.baselineFinding(),
                sample.baselineSeverity(),
                sample.baselineConfidence(),
                sample.baselineBlocking(),
                sample.baselineRisk()
            );
            assertThat(sample.knownGap()).isEqualTo(!sameContract(sample));
        });

        Map<String, List<GoldenCase>> casesByRule = cases.stream()
            .collect(Collectors.groupingBy(GoldenCase::ruleId));
        assertThat(casesByRule.keySet()).containsExactlyInAnyOrderElementsOf(HIGH_RISK_RULE_IDS);
        casesByRule.forEach((ruleId, ruleCases) -> {
            assertThat(ruleCases)
                .as(ruleId + " positive golden cases")
                .filteredOn(GoldenCase::expectedFinding)
                .hasSize(10);
            assertThat(ruleCases)
                .as(ruleId + " negative golden cases")
                .filteredOn(sample -> !sample.expectedFinding())
                .hasSize(10);
        });

        assertThat(cases).filteredOn(GoldenCase::knownGap).hasSize(35);
        assertThat(cases)
            .filteredOn(sample -> sample.knownGap() && !sample.headFileContent().isBlank())
            .extracting(GoldenCase::ruleId)
            .containsOnly("RG-AUTH-001");
    }

    @TestFactory
    Stream<DynamicTest> capturedBaselineCanBeReplayedOffline() throws IOException {
        return loadCases().stream().map(sample -> DynamicTest.dynamicTest(sample.id(), () -> {
            ReviewResult result = reviewerFor(sample.ruleId()).review(new PullRequestDiff(
                "golden",
                "offline-replay",
                1,
                List.of(new PullRequestChangedFile(
                    sample.filePath(),
                    "modified",
                    1,
                    0,
                    sample.patch()
                ))
            ));

            List<ReviewFindingResult> targetFindings = result.findings().stream()
                .filter(finding -> sample.ruleId().equals(finding.ruleId()))
                .toList();
            assertThat(targetFindings)
                .as(sample.rationale())
                .hasSize(sample.baselineFinding() ? 1 : 0);
            assertThat(result.riskLevel()).isEqualTo(sample.baselineRisk());
            if (sample.baselineFinding()) {
                ReviewFindingResult finding = targetFindings.getFirst();
                assertThat(finding.severity()).isEqualTo(sample.baselineSeverity());
                assertThat(finding.confidence()).isEqualTo(sample.baselineConfidence());
                assertThat(finding.isBlocking()).isEqualTo(sample.baselineBlocking());
                assertThat(finding.lineNumber()).isPositive();
            }
        }));
    }

    private RuleBasedPullRequestReviewer reviewerFor(String ruleId) {
        ReviewRuleProvider provider = org.mockito.Mockito.mock(ReviewRuleProvider.class);
        org.mockito.Mockito.when(provider.getRulesById()).thenReturn(ReviewRuleTestFixtures.settingsFor(ruleId));
        RuleMatchFactory matchFactory = new RuleMatchFactory();
        ReviewRule rule = switch (ruleId) {
            case "RG-AUTH-001" -> new ControllerAuthorizationGuardRule(matchFactory);
            case "RG-SECRET-001" -> new SensitiveLiteralRule(matchFactory);
            case "RG-LOG-001" -> new SensitiveLoggingRule(matchFactory);
            case "RG-MQ-001" -> new RabbitMessagePublishRule(matchFactory);
            case "RG-GH-001" -> new GithubCommentDirectPublishRule(matchFactory);
            case "RG-DB-002" -> new DestructiveMigrationRule(matchFactory);
            case "RG-DB-003" -> new RequiredColumnWithoutDefaultRule(matchFactory);
            default -> throw new IllegalArgumentException("Unsupported golden rule: " + ruleId);
        };
        return new RuleBasedPullRequestReviewer(provider, List.of(rule), List.of());
    }

    private List<GoldenCase> loadCases() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(GOLDEN_CASES)) {
            assertThat(stream).as(GOLDEN_CASES).isNotNull();
            return objectMapper.readValue(stream, new TypeReference<>() {
            });
        }
    }

    private void assertContractIsComplete(
        boolean finding,
        String severity,
        String confidence,
        boolean blocking,
        String risk
    ) {
        if (finding) {
            assertThat(severity).isEqualTo("HIGH");
            assertThat(confidence).isEqualTo("HIGH");
            assertThat(blocking).isTrue();
            assertThat(risk).isEqualTo("HIGH");
            return;
        }
        assertThat(severity).isEqualTo("NONE");
        assertThat(confidence).isEqualTo("NONE");
        assertThat(blocking).isFalse();
        assertThat(risk).isEqualTo("INFO");
    }

    private boolean sameContract(GoldenCase sample) {
        return sample.expectedFinding() == sample.baselineFinding()
            && sample.expectedSeverity().equals(sample.baselineSeverity())
            && sample.expectedConfidence().equals(sample.baselineConfidence())
            && sample.expectedBlocking() == sample.baselineBlocking()
            && sample.expectedRisk().equals(sample.baselineRisk());
    }

    private record GoldenCase(
        String id,
        String ruleId,
        String filePath,
        String patch,
        String headFileContent,
        String rationale,
        boolean expectedFinding,
        String expectedSeverity,
        String expectedConfidence,
        boolean expectedBlocking,
        String expectedRisk,
        boolean baselineFinding,
        String baselineSeverity,
        String baselineConfidence,
        boolean baselineBlocking,
        String baselineRisk,
        boolean knownGap
    ) {
    }
}
