package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.LlmReviewContextProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmReviewContextBuilderTest {

    @Test
    void rendersStableSourceRelatedTestConfigAndRulePolicyContext() {
        ReviewRuleProvider ruleProvider = mock(ReviewRuleProvider.class);
        when(ruleProvider.getRulesById()).thenReturn(Map.of(
            "RG-AUTH-001",
            new ReviewRuleSettings(
                "RG-AUTH-001",
                "ENABLED",
                "**/*Controller.java",
                "HIGH",
                92,
                EnforcementMode.COMMENT,
                "A mutable endpoint without an authorization boundary",
                "Class-level authorization or an approved gateway already protects the endpoint",
                "Detect newly exposed mutable endpoints without authorization"
            )
        ));
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        properties.setMaxRelatedFiles(4);
        LlmReviewContextBuilder builder = new LlmReviewContextBuilder(
            ruleProvider,
            properties,
            new DiffRiskClassifier()
        );

        PullRequestChangedFile primary = available(
            "src/main/java/com/example/AdminController.java",
            """
                package com.example;
                public class AdminController implements AdminApi {
                    public void update() {
                        repository.save();
                    }
                }
                """,
            "@@ -2,3 +2,4 @@\n+public class AdminController implements AdminApi {"
        );
        PullRequestChangedFile contract = available(
            "src/main/java/com/example/AdminApi.java",
            "public interface AdminApi { void update(); }",
            "@@ -1,1 +1,1 @@\n+public interface AdminApi { void update(); }"
        );
        PullRequestChangedFile caller = available(
            "src/main/java/com/example/AdminFacade.java",
            "public class AdminFacade { AdminController controller; }",
            "@@ -1,1 +1,1 @@\n+public class AdminFacade { AdminController controller; }"
        );
        PullRequestChangedFile test = available(
            "src/test/java/com/example/AdminControllerTest.java",
            "class AdminControllerTest { AdminController subject; }",
            "@@ -1,1 +1,1 @@\n+class AdminControllerTest { AdminController subject; }"
        );
        PullRequestChangedFile config = available(
            "src/main/resources/application.yml",
            "security:\n  admin-role: ADMIN",
            "@@ -1,1 +1,2 @@\n+security:\n+  admin-role: ADMIN"
        );
        PullRequestDiff fullDiff = diff(List.of(primary, contract, caller, test, config));
        LlmReviewContext context = builder.build(fullDiff);
        String rendered = context.renderFor(diff(List.of(primary)));

        assertThat(rendered).contains(
            "Context version: review-context-v2",
            "[SOURCE] src/main/java/com/example/AdminController.java:L1-L6",
            "L3:     public void update()",
            "[INTERFACE] src/main/java/com/example/AdminApi.java:L1-L1",
            "[DIRECT_CALLER] src/main/java/com/example/AdminFacade.java:L1-L1",
            "[TEST] src/test/java/com/example/AdminControllerTest.java:L1-L1",
            "[CONFIG] src/main/resources/application.yml:L1-L2",
            "[ENABLED_RULE_POLICY]",
            "RG-AUTH-001 | severity=HIGH | confidence=92 | mode=COMMENT",
            "description=Detect newly exposed mutable endpoints without authorization",
            "positive=A mutable endpoint without an authorization boundary",
            "falsePositive=Class-level authorization"
        );
        assertThat(rendered.length()).isLessThanOrEqualTo(properties.getMaxTotalChars());
    }

    @Test
    void retainsHigherRiskSlicesFirstAndRecordsBudgetTruncation() {
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        properties.setMaxTotalChars(140);
        properties.setMaxSliceChars(120);
        LlmReviewContextBuilder builder = new LlmReviewContextBuilder(
            null,
            properties,
            new DiffRiskClassifier()
        );
        PullRequestChangedFile ordinary = available(
            "src/main/java/com/example/Value.java",
            "x".repeat(100),
            "@@ -1,1 +1,1 @@\n+value"
        );
        PullRequestChangedFile migration = available(
            "src/main/resources/db/migration/V1__drop.sql",
            "DROP TABLE legacy_" + "x".repeat(80),
            "@@ -1,1 +1,1 @@\n+DROP TABLE legacy"
        );

        LlmReviewContext context = builder.build(diff(List.of(ordinary, migration)));

        assertThat(context.slices()).extracting(LlmContextSlice::filePath)
            .containsExactly("src/main/resources/db/migration/V1__drop.sql");
        assertThat(context.budgetTruncated()).isTrue();
        assertThat(context.renderFor(diff(List.of(migration))))
            .contains("context_budget_truncated")
            .hasSizeLessThanOrEqualTo(140);
    }

    @Test
    void reportsUnavailableExactHeadContextWithoutInventingSource() {
        PullRequestChangedFile unavailable = new PullRequestChangedFile(
            "src/main/java/com/example/AdminController.java",
            "modified",
            1,
            0,
            "@@ -1,0 +1,1 @@\n+class AdminController {}",
            ChangedFileContext.status(
                "src/main/java/com/example/AdminController.java",
                "head-a",
                ChangedFileContext.Status.UNAVAILABLE,
                "fetch_failed"
            )
        );
        LlmReviewContext context = new LlmReviewContextBuilder().build(diff(List.of(unavailable)));
        String rendered = context.renderFor(diff(List.of(unavailable)));

        assertThat(context.hasSliceFor(unavailable.filename())).isFalse();
        assertThat(context.unavailableFor(unavailable.filename())).isTrue();
        assertThat(rendered).contains(
            "[CONTEXT_LIMITATIONS]",
            "AdminController.java=UNAVAILABLE:fetch_failed"
        );
        assertThat(rendered).doesNotContain("[SOURCE]");
    }

    @Test
    void rejectsAvailableContentFromADifferentHeadSha() {
        String path = "src/main/java/com/example/AdminController.java";
        PullRequestChangedFile stale = new PullRequestChangedFile(
            path,
            "modified",
            1,
            0,
            "@@ -1,0 +1,1 @@\n+class AdminController {}",
            ChangedFileContext.available(path, "stale-head", "class AdminController {}")
        );

        LlmReviewContext context = new LlmReviewContextBuilder().build(diff(List.of(stale)));

        assertThat(context.hasSliceFor(path)).isFalse();
        assertThat(context.unavailableFor(path)).isTrue();
        assertThat(context.renderFor(diff(List.of(stale)))).contains("UNAVAILABLE:head_sha_mismatch");
    }

    @Test
    void recordsMissingContextAndSkipsExcludedOrDeletedFiles() {
        PullRequestChangedFile missing = new PullRequestChangedFile(
            "src/main/java/Missing.java", "modified", 1, 0, "", null
        );
        PullRequestChangedFile excluded = new PullRequestChangedFile(
            "secret.pem", "modified", 0, 0, "",
            ChangedFileContext.status("secret.pem", "head-a", ChangedFileContext.Status.EXCLUDED, "secret_file")
        );
        PullRequestChangedFile deleted = new PullRequestChangedFile(
            "src/main/java/Deleted.java", "deleted", 0, 10, "",
            ChangedFileContext.status(
                "src/main/java/Deleted.java", "head-a", ChangedFileContext.Status.DELETED, "deleted_file"
            )
        );
        PullRequestChangedFile unavailable = new PullRequestChangedFile(
            "src/main/java/Unavailable.java", "modified", 1, 0, "",
            ChangedFileContext.status(
                "src/main/java/Unavailable.java", "head-a", ChangedFileContext.Status.UNAVAILABLE, "fetch_failed"
            )
        );

        LlmReviewContext context = new LlmReviewContextBuilder().build(
            diff(List.of(missing, excluded, deleted, unavailable))
        );

        assertThat(context.slices()).isEmpty();
        assertThat(context.limitations())
            .extracting(LlmReviewContext.ContextLimitation::filePath)
            .containsExactlyInAnyOrder("src/main/java/Missing.java", "src/main/java/Unavailable.java");
        assertThat(context.renderFor(diff(List.of(missing, unavailable))))
            .contains("legacy_or_offline_input", "fetch_failed");
    }

    @Test
    void recordsEmptyTextAndPrimaryBudgetTruncation() {
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        properties.setMaxTotalChars(4_096);
        properties.setMaxSliceChars(4_096);
        LlmReviewContextBuilder builder = new LlmReviewContextBuilder(null, properties, new DiffRiskClassifier());
        PullRequestChangedFile empty = available(
            "src/main/java/Empty.java", "", "@@ -1,0 +1,1 @@\n+class Empty {}"
        );
        PullRequestChangedFile large = available(
            "src/main/java/Large.java", "class Large {" + "x".repeat(4_200) + "}",
            "@@ -1,1 +1,1 @@\n+class Large {}"
        );

        LlmReviewContext context = builder.build(diff(List.of(empty, large)));

        assertThat(context.limitations()).extracting(LlmReviewContext.ContextLimitation::reason)
            .contains("empty_text_context");
        assertThat(context.budgetTruncated()).isTrue();
    }

    @Test
    void degradesWhenSemanticProviderThrows() {
        RepositorySemanticContextProvider provider = mock(RepositorySemanticContextProvider.class);
        PullRequestDiff pullRequest = diff(List.of(available(
            "src/main/java/OrderService.java", "class OrderService {}", "@@ -1,1 +1,1 @@\n+class OrderService {}"
        )));
        doThrow(new IllegalStateException("index down")).when(provider).load(pullRequest);

        LlmReviewContext context = new LlmReviewContextBuilder(
            null, new LlmReviewContextProperties(), new DiffRiskClassifier(), provider
        ).build(pullRequest);

        assertThat(context.repositoryContextSummary()).contains("semantic_context_provider_failed");
        assertThat(context.limitations()).extracting(LlmReviewContext.ContextLimitation::reason)
            .contains("semantic_context_provider_failed");
    }

    @Test
    void handlesUnavailableAndEmptyRulePolicy() {
        ReviewRuleProvider provider = mock(ReviewRuleProvider.class);
        doThrow(new IllegalStateException("policy down")).when(provider).getRulesById();
        LlmReviewContext unavailable = new LlmReviewContextBuilder(
            provider, new LlmReviewContextProperties(), new DiffRiskClassifier()
        ).build(diff(List.of()));
        assertThat(unavailable.rulePolicyContext()).isEqualTo("rule_policy_unavailable");

        doReturn(Map.of()).when(provider).getRulesById();
        LlmReviewContext empty = new LlmReviewContextBuilder(
            provider, new LlmReviewContextProperties(), new DiffRiskClassifier()
        ).build(diff(List.of()));
        assertThat(empty.rulePolicyContext()).isEqualTo("no_enabled_rules");
    }

    @Test
    void boundsBlankAndLongRulePolicyText() {
        ReviewRuleSettings rule = new ReviewRuleSettings(
            "RG-TEXT-001", "ENABLED", "**/*.java", "LOW", 50, EnforcementMode.COMMENT,
            "   ", "guidance " + "x".repeat(500), "description " + "y".repeat(500)
        );
        ReviewRuleProvider provider = mock(ReviewRuleProvider.class);
        when(provider.getRulesById()).thenReturn(Map.of("RG-TEXT-001", rule));
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        properties.setMaxRuleTextChars(80);

        LlmReviewContext context = new LlmReviewContextBuilder(
            provider, properties, new DiffRiskClassifier()
        ).build(diff(List.of()));

        assertThat(context.rulePolicyContext()).contains("description y", "positive=-", "falsePositive=guidance");
        assertThat(context.rulePolicyContext()).doesNotContain("y".repeat(81));
    }

    @Test
    void mergesDefaultBranchSemanticSlicesAndSummaryAfterPrimarySlices() {
        RepositorySemanticContextProvider provider = mock(RepositorySemanticContextProvider.class);
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        LlmContextSlice caller = new LlmContextSlice(
            "src/main/java/com/example/OrderFacade.java",
            1,
            1,
            LlmContextSlice.Role.SOURCE,
            "L1: OrderService service;",
            java.util.Set.of("OrderService"),
            100
        );
        PullRequestDiff pullRequest = diff(List.of(available(
            "src/main/java/com/example/OrderService.java",
            "class OrderService {}",
            "@@ -1,0 +1,1 @@\n+class OrderService {}"
        )));
        when(provider.load(pullRequest)).thenReturn(new RepositorySemanticContext(
            "main",
            List.of(caller),
            List.of(),
            false,
            "branch=main; deterministic=true; indexedFiles=1"
        ));

        LlmReviewContext context = new LlmReviewContextBuilder(
            null,
            properties,
            new DiffRiskClassifier(),
            provider
        ).build(pullRequest);

        assertThat(context.repositoryContextSummary()).contains("branch=main", "deterministic=true");
        assertThat(context.renderFor(pullRequest))
            .contains("[REPOSITORY_SEMANTIC_CONTEXT]", "OrderFacade.java", "[DIRECT_CALLER]");
    }

    private PullRequestChangedFile available(String path, String content, String patch) {
        return new PullRequestChangedFile(
            path,
            "modified",
            1,
            0,
            patch,
            ChangedFileContext.available(path, "head-a", content)
        );
    }

    private PullRequestDiff diff(List<PullRequestChangedFile> files) {
        return new PullRequestDiff("octocat", "Hello-World", 1, "head-a", files);
    }
}
