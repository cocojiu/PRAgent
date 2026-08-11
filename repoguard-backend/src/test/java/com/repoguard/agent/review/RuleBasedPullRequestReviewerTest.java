package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.ReviewHumanReviewProperties;
import com.repoguard.agent.review.ReviewRuleProvider;
import com.repoguard.agent.review.ReviewRuleSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuleBasedPullRequestReviewerTest {

    private final ReviewRuleProvider reviewRuleProvider = org.mockito.Mockito.mock(ReviewRuleProvider.class);
    private final RuleBasedPullRequestReviewer reviewer = ReviewRuleTestFixtures.defaultReviewer(reviewRuleProvider);

    @Test
    void usesInjectedRulePluginsInsteadOfHardcodedRuleList() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.settingsFor("RG-CUSTOM-001"));
        RuleMatchFactory matchFactory = new RuleMatchFactory();
        RuleBasedPullRequestReviewer pluginReviewer = new RuleBasedPullRequestReviewer(
            reviewRuleProvider,
            List.of(customRulePlugin(matchFactory)),
            List.of()
        );

        ReviewResult result = pluginReviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file(
                "src/main/java/com/example/PluginDemo.java",
                """
                    @@ -8,0 +9,1 @@
                    +dangerousCall();
                    """
            ))
        ));

        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .containsExactly("RG-CUSTOM-001");
    }

    @Test
    void changingOnlyRuleSeverityRecalculatesFindingRiskAndHumanReview() {
        RuleBasedPullRequestReviewer pluginReviewer = new RuleBasedPullRequestReviewer(
            reviewRuleProvider,
            List.of(customRulePlugin(new RuleMatchFactory())),
            List.of()
        );
        PullRequestDiff diff = new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file(
                "src/main/java/com/example/PluginDemo.java",
                """
                    @@ -8,0 +9,1 @@
                    +dangerousCall();
                    """
            ))
        );
        HumanReviewPolicyEvaluator humanReviewPolicy = new HumanReviewPolicyEvaluator(
            new RiskLevelRanker(),
            new ReviewHumanReviewProperties()
        );

        when(reviewRuleProvider.getRulesById()).thenReturn(Map.of(
            "RG-CUSTOM-001",
            new ReviewRuleSettings(
                "RG-CUSTOM-001",
                "ENABLED",
                "*.java",
                "HIGH",
                95,
                EnforcementMode.BLOCK,
                "dangerousCall();",
                "Ignore generated fixtures"
            )
        ));
        ReviewResult highResult = pluginReviewer.review(diff);

        when(reviewRuleProvider.getRulesById()).thenReturn(Map.of(
            "RG-CUSTOM-001",
            new ReviewRuleSettings(
                "RG-CUSTOM-001",
                "ENABLED",
                "*.java",
                "LOW",
                95,
                EnforcementMode.BLOCK,
                "dangerousCall();",
                "Ignore generated fixtures"
            )
        ));
        ReviewResult lowResult = pluginReviewer.review(diff);

        assertThat(highResult.findings().getFirst().severity()).isEqualTo("HIGH");
        assertThat(highResult.findings().getFirst().isBlocking()).isTrue();
        assertThat(highResult.riskLevel()).isEqualTo("HIGH");
        assertThat(humanReviewPolicy.requiresHumanReview(highResult)).isTrue();
        assertThat(lowResult.findings().getFirst().severity()).isEqualTo("LOW");
        assertThat(lowResult.findings().getFirst().isBlocking()).isFalse();
        assertThat(lowResult.riskLevel()).isEqualTo("LOW");
        assertThat(humanReviewPolicy.requiresHumanReview(lowResult)).isFalse();
    }

    @Test
    void downgradesContextDependentCandidateWhenExactHeadContentIsUnavailable() {
        when(reviewRuleProvider.getRulesById())
            .thenReturn(ReviewRuleTestFixtures.settingsFor(ControllerAuthorizationGuardRule.RULE_ID));
        String path = "src/main/java/com/example/AdminController.java";
        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            "reviewed-head",
            List.of(new PullRequestChangedFile(
                path,
                "modified",
                1,
                0,
                "@@ -10,0 +11,1 @@\n+@DeleteMapping(\"/users/{id}\")",
                ChangedFileContext.status(
                    path,
                    "reviewed-head",
                    ChangedFileContext.Status.UNAVAILABLE,
                    "fetch_failed"
                )
            ))
        ));

        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("HIGH");
            assertThat(finding.confidence()).isEqualTo("MEDIUM");
            assertThat(finding.isBlocking()).isFalse();
            assertThat(finding.policyReason()).isEqualTo("evidence_not_verified");
        });
    }

    @Test
    void usesInjectedPullRequestRulePluginsInStableOrder() {
        when(reviewRuleProvider.getRulesById()).thenReturn(Map.of(
            "RG-PR-001", rule("RG-PR-001", "ENABLED", "*"),
            "RG-PR-002", rule("RG-PR-002", "ENABLED", "*")
        ));
        RuleMatchFactory matchFactory = new RuleMatchFactory();
        RuleBasedPullRequestReviewer pluginReviewer = new RuleBasedPullRequestReviewer(
            reviewRuleProvider,
            ReviewRuleTestFixtures.defaultLineRules(matchFactory),
            List.of(
                customPullRequestRule(matchFactory, "RG-PR-002", 20),
                customPullRequestRule(matchFactory, "RG-PR-001", 10)
            )
        );

        ReviewResult result = pluginReviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file("docs/README.md", """
                @@ -1,1 +1,2 @@
                 # Demo
                +New details.
                """))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .containsExactly("RG-PR-001", "RG-PR-002");
    }

    @Test
    void skipsDisabledRulesWhenReviewingPatch() {
        when(reviewRuleProvider.getRulesById()).thenReturn(
            ReviewRuleTestFixtures.defaultSettingsWith(disabledRule("RG-JAVA-002"))
        );

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(new PullRequestChangedFile(
                "src/App.java",
                "modified",
                2,
                0,
                """
                    @@ -1,1 +1,3 @@
                     class App {
                    +System.out.println("debug");
                    +Thread.sleep(1000);
                     }
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .contains("RG-JAVA-003")
            .doesNotContain("RG-JAVA-002");
    }

    @Test
    void skipsRulesWhenFilePatternDoesNotMatch() {
        when(reviewRuleProvider.getRulesById())
            .thenReturn(Map.of("RG-JAVA-002", rule("RG-JAVA-002", "ENABLED", "*.java")));

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(new PullRequestChangedFile(
                "docs/README.md",
                "modified",
                1,
                0,
                """
                    @@ -1,1 +1,2 @@
                     # Demo
                    +System.out.println("debug");
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .doesNotContain("RG-JAVA-002");
    }

    @Test
    void filtersNonApplicableRulesOnceBeforeScanningLargePatches() {
        String applicableRuleId = "RG-CUSTOM-JAVA";
        String nonApplicableRuleId = "RG-CUSTOM-SQL";
        when(reviewRuleProvider.getRulesById()).thenReturn(Map.of(
            applicableRuleId,
            rule(applicableRuleId, "ENABLED", "*.java"),
            nonApplicableRuleId,
            rule(nonApplicableRuleId, "ENABLED", "*.sql")
        ));
        AtomicInteger applicableEvaluations = new AtomicInteger();
        AtomicInteger nonApplicableEvaluations = new AtomicInteger();
        RuleBasedPullRequestReviewer pluginReviewer = new RuleBasedPullRequestReviewer(
            reviewRuleProvider,
            List.of(
                countingRule(applicableRuleId, applicableEvaluations),
                countingRule(nonApplicableRuleId, nonApplicableEvaluations)
            ),
            List.of()
        );
        StringBuilder patch = new StringBuilder("@@ -0,0 +1,2000 @@\n");
        for (int index = 0; index < 2_000; index++) {
            patch.append("+String value").append(index).append(" = \"example\";\n");
        }

        pluginReviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file("src/main/java/com/example/LargeChange.java", patch.toString()))
        ));

        assertThat(applicableEvaluations).hasValue(2_000);
        assertThat(nonApplicableEvaluations).hasValue(0);
    }

    @Test
    void scansEnterpriseSizedPatchWithinTheBoundedCpuBudget() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());
        List<PullRequestChangedFile> files = new ArrayList<>();
        for (int fileIndex = 0; fileIndex < 241; fileIndex++) {
            StringBuilder patch = new StringBuilder("@@ -0,0 +1,60 @@\n");
            for (int lineIndex = 0; lineIndex < 60; lineIndex++) {
                patch.append("+String value")
                    .append(lineIndex)
                    .append(" = \"non-sensitive-example-value\";\n");
            }
            files.add(file(
                "src/main/java/com/example/LargeChange" + fileIndex + ".java",
                patch.toString()
            ));
        }

        ReviewResult result = assertTimeout(
            Duration.ofSeconds(10),
            () -> reviewer.review(new PullRequestDiff("octocat", "Hello-World", 1, files))
        );

        assertThat(result).isNotNull();
    }

    @Test
    void treatsRegexMetacharactersInFilePatternsAsLiterals() {
        when(reviewRuleProvider.getRulesById())
            .thenReturn(Map.of("RG-CUSTOM-001", rule("RG-CUSTOM-001", "ENABLED", "src/(api)/[v1]/*.java")));
        RuleMatchFactory matchFactory = new RuleMatchFactory();
        RuleBasedPullRequestReviewer pluginReviewer = new RuleBasedPullRequestReviewer(
            reviewRuleProvider,
            List.of(customRulePlugin(matchFactory)),
            List.of()
        );

        ReviewResult result = pluginReviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(new PullRequestChangedFile(
                "src/(api)/[v1]/UserController.java",
                "modified",
                1,
                0,
                """
                    @@ -1,1 +1,2 @@
                     class UserController {
                    +dangerousCall();
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .contains("RG-CUSTOM-001");
    }

    @Test
    void detectsProjectSpecificGovernanceRules() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(
                file(
                    "src/main/java/com/example/UserController.java",
                    """
                        @@ -10,0 +11,1 @@
                        +@PostMapping("/users")
                        """
                ),
                file(
                    "src/main/java/com/example/ReviewTaskService.java",
                    """
                        @@ -20,0 +21,1 @@
                        +task.setStatus("REVIEWING");
                        """
                ),
                file(
                    "src/main/java/com/example/RabbitPublisher.java",
                    """
                        @@ -30,0 +31,1 @@
                        +rabbitTemplate.convertAndSend(exchange, routingKey, message);
                        """
                ),
                file(
                    "src/main/java/com/example/ProfileClient.java",
                    """
                        @@ -40,0 +41,1 @@
                        +return restClient.get().uri(url).retrieve().body(Profile.class);
                        """
                ),
                file(
                    "src/main/java/com/example/SecurityConfig.java",
                    """
                        @@ -50,0 +51,1 @@
                        +String githubToken = "%s";
                        """.formatted(SyntheticCredentialFixtures.githubToken())
                ),
                file(
                    "src/main/java/com/example/AuditLogger.java",
                    """
                        @@ -60,0 +61,1 @@
                        +log.info("webhook secret {}", webhookSecret);
                        """
                )
            )
        ));

        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .containsExactlyInAnyOrder(
                "RG-AUTH-001",
                "RG-STATE-001",
                "RG-MQ-001",
                "RG-EXT-001",
                "RG-SECRET-001",
                "RG-LOG-001",
                "RG-API-001"
            );
        assertThat(result.findings())
            .filteredOn(finding -> "HIGH".equals(finding.severity()))
            .allSatisfy(finding -> {
                assertThat(finding.confidence()).isEqualTo("HIGH");
                assertThat(finding.isBlocking()).isTrue();
                assertThat(finding.evidence()).contains(finding.ruleId());
                assertThat(finding.impact()).isNotBlank();
                assertThat(finding.fixExample()).isEqualTo(finding.recommendation());
                assertThat(finding.reviewDimension()).isNotBlank();
            });
    }

    @Test
    void honorsDisabledProjectSpecificRules() {
        when(reviewRuleProvider.getRulesById()).thenReturn(Map.of("RG-AUTH-001", disabledRule("RG-AUTH-001")));

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file(
                "src/main/java/com/example/AdminController.java",
                """
                    @@ -10,0 +11,1 @@
                    +@DeleteMapping("/users/{id}")
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .doesNotContain("RG-AUTH-001");
    }

    @Test
    void detectsMigrationAndGithubWritebackGovernanceRules() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(
                file(
                    "src/main/resources/db/migration/V31__drop_legacy_table.sql",
                    """
                        @@ -0,0 +1,1 @@
                        +drop table legacy_review_task;
                        """
                ),
                file(
                    "src/main/resources/db/migration/V32__add_required_column.sql",
                    """
                        @@ -0,0 +1,1 @@
                        +alter table review_task add column reviewer_id bigint not null;
                        """
                ),
                file(
                    "src/main/java/com/example/github/CommentPublisher.java",
                    """
                        @@ -20,0 +21,1 @@
                        +githubPullRequestClient.publishPullRequestComments(task, drafts);
                        """
                )
            )
        ));

        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .containsExactlyInAnyOrder("RG-DB-002", "RG-DB-003", "RG-GH-001");
        assertThat(result.findings())
            .allSatisfy(finding -> {
                assertThat(finding.isBlocking()).isTrue();
                assertThat(finding.confidence()).isEqualTo("HIGH");
                assertThat(finding.impact()).isNotBlank();
            });
    }

    @Test
    void allowsCompatibleMigrationWithDefaultValue() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file(
                "src/main/resources/db/migration/V33__add_status.sql",
                """
                    @@ -0,0 +1,1 @@
                    +alter table review_task add column review_source varchar(32) not null default 'manual';
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .doesNotContain("RG-DB-003");
    }

    @Test
    void detectsControllerApiChangeWithoutTestCoverageInSamePullRequest() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file(
                "src/main/java/com/example/order/OrderController.java",
                """
                    @@ -18,0 +19,1 @@
                    +@GetMapping("/orders/{id}")
                    """
            ))
        ));

        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .containsExactly("RG-API-001");
        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(finding.lineNumber()).isEqualTo(19);
        assertThat(finding.confidence()).isEqualTo("HIGH");
        assertThat(finding.isBlocking()).isFalse();
        assertThat(finding.reviewDimension()).isEqualTo("API_CONTRACT_RULE");
    }

    @Test
    void detectsIndentedControllerApiChangeWithoutTestCoverage() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file(
                "src/main/java/com/example/order/OrderController.java",
                """
                    @@ -18,0 +19,3 @@
                    +    @GetMapping("/orders/{id}")
                    +    public Order getOrder() {
                    +    }
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .contains("RG-API-001");
    }

    @Test
    void skipsAuthFindingWhenMutatingControllerHasAuthorizationGuardButStillReportsTestGap() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(file(
                "src/main/java/com/example/order/OrderController.java",
                """
                    @@ -18,0 +19,4 @@
                    +    @RequireRole("ADMIN")
                    +    @PostMapping("/orders")
                    +    public Order createOrder() {
                    +    }
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .contains("RG-API-001")
            .doesNotContain("RG-AUTH-001");
    }

    @Test
    void skipsControllerApiTestGapWhenPullRequestContainsTestChange() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());

        ReviewResult result = reviewer.review(new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(
                file(
                    "src/main/java/com/example/order/OrderController.java",
                    """
                        @@ -18,0 +19,1 @@
                        +@GetMapping("/orders/{id}")
                        """
                ),
                file(
                    "src/test/java/com/example/order/OrderControllerTest.java",
                    """
                        @@ -30,0 +31,1 @@
                        +mockMvc.perform(get("/orders/1")).andExpect(status().isOk());
                        """
                )
            )
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .doesNotContain("RG-API-001");
    }

    private ReviewRuleSettings disabledRule(String id) {
        return rule(id, "DISABLED", "");
    }

    private ReviewRuleSettings rule(String id, String status, String filePatterns) {
        return new ReviewRuleSettings(id, status, filePatterns);
    }

    private PullRequestChangedFile file(String filename, String patch) {
        return new PullRequestChangedFile(filename, "modified", 1, 0, patch);
    }

    private ReviewRule customRulePlugin(RuleMatchFactory matchFactory) {
        return new ReviewRule() {
            @Override
            public String id() {
                return "RG-CUSTOM-001";
            }

            @Override
            public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
                if (!context.trimmedLine().contains("dangerousCall")) {
                    return Optional.empty();
                }
                return Optional.of(matchFactory.match(
                    id(),
                    context.filePath(),
                    context.lineNumber(),
                    "Custom rule plugin detected a dangerous call",
                    "Replace the dangerous call with a governed abstraction."
                ));
            }
        };
    }

    private ReviewRule countingRule(String ruleId, AtomicInteger evaluations) {
        return new ReviewRule() {
            @Override
            public String id() {
                return ruleId;
            }

            @Override
            public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
                assertThat(context.isApplicable(ruleId)).isTrue();
                evaluations.incrementAndGet();
                return Optional.empty();
            }
        };
    }

    private PullRequestReviewRule customPullRequestRule(RuleMatchFactory matchFactory, String ruleId, int order) {
        return new PullRequestReviewRule() {
            @Override
            public String id() {
                return ruleId;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public List<RuleMatch> evaluate(
                PullRequestDiff diff,
                Map<String, ReviewRuleSettings> configuredRules
            ) {
                return List.of(matchFactory.match(
                    ruleId,
                    "docs/README.md",
                    null,
                    "Custom PR level rule",
                    "Handle the custom PR level rule."
                ));
            }
        };
    }
}
