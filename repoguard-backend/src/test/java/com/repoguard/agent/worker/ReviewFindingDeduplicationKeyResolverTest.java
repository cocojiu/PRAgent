package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewFindingResult;
import org.junit.jupiter.api.Test;

class ReviewFindingDeduplicationKeyResolverTest {

    private final ReviewFindingDeduplicationKeyResolver resolver = new ReviewFindingDeduplicationKeyResolver();

    @Test
    void resolvesSameKeyForAccessControlFindingsWithDifferentWordingInSameScope() {
        String firstKey = resolver.key(finding(
            "LOW",
            "LLM",
            "LLM",
            "src\\main\\java\\com\\example\\AdminController.java",
            20,
            "Admin endpoint can be called without authorization",
            "Add role based access control"
        ));
        String secondKey = resolver.key(finding(
            "HIGH",
            "RULE",
            "RG-AUTH-001",
            "src/main/java/com/example/AdminController.java",
            22,
            "Missing permission gate on admin API",
            "Require ADMIN role before executing the handler"
        ));

        assertThat(firstKey).isEqualTo(secondKey);
        assertThat(firstKey).isEqualTo("src/main/java/com/example/admincontroller.java|security:access_control|line:2");
    }

    @Test
    void keepsDifferentIssueTypesSeparateInSameFileScope() {
        String loggingKey = resolver.key(finding(
            "LOW",
            "LLM",
            "LLM",
            "src/main/java/com/example/App.java",
            30,
            "System.out.println is used in request handling",
            "Use structured logging"
        ));
        String sleepKey = resolver.key(finding(
            "MEDIUM",
            "RULE",
            "RG-JAVA-003",
            "src/main/java/com/example/App.java",
            31,
            "Thread.sleep blocks the worker thread",
            "Replace fixed sleep with async waiting"
        ));

        assertThat(loggingKey).isNotEqualTo(sleepKey);
        assertThat(loggingKey).contains("logging:stdout");
        assertThat(sleepKey).contains("concurrency:fixed_sleep");
    }

    @Test
    void includesStableTermsForGenericFindings() {
        String key = resolver.key(finding(
            "LOW",
            "LLM",
            "LLM",
            "src/main/java/com/example/App.java",
            40,
            "Button label is unclear",
            "Use a clear action label"
        ));

        assertThat(key).isEqualTo("src/main/java/com/example/app.java|general:button|line:4|button,label,unclear,clear,action");
    }

    private ReviewFindingResult finding(
        String severity,
        String source,
        String ruleId,
        String filePath,
        Integer lineNumber,
        String message,
        String recommendation
    ) {
        return new ReviewFindingResult(severity, source, ruleId, filePath, lineNumber, message, recommendation);
    }
}
