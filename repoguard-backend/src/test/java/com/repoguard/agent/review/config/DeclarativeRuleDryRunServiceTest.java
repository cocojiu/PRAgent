package com.repoguard.agent.review.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.DeclarativeRuleDryRunFile;
import com.repoguard.agent.dto.DeclarativeRuleDryRunRequest;
import com.repoguard.agent.review.DeclarativeRuleMatcher;
import com.repoguard.agent.review.DeclarativeRulePolicy;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.ReviewRuleProvider;
import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeclarativeRuleDryRunServiceTest {

    private static final String SECRET_KEYWORD = "pass" + "word";

    private final ReviewRuleProvider provider = mock(ReviewRuleProvider.class);
    private final DeclarativeRuleDryRunService service = new DeclarativeRuleDryRunService(
        provider,
        new DeclarativeRuleMatcher(new DeclarativeRulePolicy())
    );

    @Test
    void replaysAddedLinesAndReportsMatchedFilesAndLines() {
        when(provider.getRulesById()).thenReturn(Map.of("RG-CUSTOM-001", settings()));

        var files = new java.util.ArrayList<DeclarativeRuleDryRunFile>();
        files.add(new DeclarativeRuleDryRunFile(
            "src/App.java",
            "@@ -0,0 +1,3 @@\n+String " + SECRET_KEYWORD + " = value;\n context\n-" + SECRET_KEYWORD + " = old;"
        ));
        files.add(new DeclarativeRuleDryRunFile("", "ignored"));
        files.add(null);
        var result = service.run("rg-custom-001", new DeclarativeRuleDryRunRequest(42L, files));

        assertThat(result.ruleId()).isEqualTo("RG-CUSTOM-001");
        assertThat(result.taskId()).isEqualTo(42L);
        assertThat(result.matchedFiles()).isEqualTo(1);
        assertThat(result.matchedLines()).isEqualTo(1);
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.filePath()).isEqualTo("src/App.java");
            assertThat(match.lineNumber()).isEqualTo(1);
            assertThat(match.evidence()).contains(SECRET_KEYWORD);
        });
    }

    @Test
    void rejectsMissingOrNonDeclarativeRules() {
        when(provider.getRulesById()).thenReturn(Map.of());

        assertThatThrownBy(() -> service.run("RG-MISSING", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only declarative rules");

        when(provider.getRulesById()).thenReturn(Map.of("RG-BUILTIN", new ReviewRuleSettings(
            "RG-BUILTIN", "ENABLED", "*.java", "HIGH", 90, EnforcementMode.COMMENT,
            "", "", "", "builtin-v1", 1, 1
        )));
        assertThatThrownBy(() -> service.run("RG-BUILTIN", new DeclarativeRuleDryRunRequest(
            1L, List.of(new DeclarativeRuleDryRunFile("App.java", "+line"))
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only declarative rules");
    }

    @Test
    void rejectsEmptyHistoricalPatchSet() {
        when(provider.getRulesById()).thenReturn(Map.of("RG-CUSTOM-001", settings()));

        assertThatThrownBy(() -> service.run(
            "RG-CUSTOM-001",
            new DeclarativeRuleDryRunRequest(1L, List.of())
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("At least one historical patch");
    }

    @Test
    void rejectsOversizedPayloadAndToleratesMalformedHunkHeaders() {
        when(provider.getRulesById()).thenReturn(Map.of("RG-CUSTOM-001", settings()));
        assertThatThrownBy(() -> service.run(
            "RG-CUSTOM-001",
            new DeclarativeRuleDryRunRequest(1L, List.of(
                new DeclarativeRuleDryRunFile("App.java", "x".repeat(2_000_001))
            ))
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("payload is too large");

        var result = service.run("RG-CUSTOM-001", new DeclarativeRuleDryRunRequest(1L, List.of(
            new DeclarativeRuleDryRunFile("App.java", "@@ malformed\n+" + SECRET_KEYWORD + " = value;\n@@ -1 +\n+" + SECRET_KEYWORD + " = value;")
        )));
        assertThat(result.matchedLines()).isEqualTo(2);

        var ignored = service.run("RG-CUSTOM-001", new DeclarativeRuleDryRunRequest(1L, java.util.Arrays.asList(
            new DeclarativeRuleDryRunFile("", "ignored"), null
        )));
        assertThat(ignored.matchedLines()).isZero();
    }

    private ReviewRuleSettings settings() {
        return new ReviewRuleSettings(
            "RG-CUSTOM-001", "ENABLED", "*.java", "HIGH", 95,
            EnforcementMode.COMMENT, "Use a secret provider", "Generated files are exempt",
            "Credential assignment", "declarative-regex-v1", 1, 1,
            DeclarativeRulePolicy.REGEX, SECRET_KEYWORD + "\\s*=", "**/generated/**"
        );
    }
}
