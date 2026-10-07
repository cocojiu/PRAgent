package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalReviewCommandTest {
    private final com.fasterxml.jackson.databind.ObjectMapper json = new JacksonConfig().objectMapper();

    private JsonNode review(Map<String, Object> request) throws Exception {
        return json.readTree(LocalReviewCommand.review(json.writeValueAsBytes(request)));
    }

    private Map<String, Object> file() {
        return Map.of("filename", "src/main/java/example/Service.java", "patch",
            "@@ -0,0 +1,3 @@\n+class Service {\n+ void run() { Thread.sleep(200); }\n+}\n");
    }

    @Test
    void runsActualDetectorsWithExplicitOfflineScopeAndNoSourceExcerpts() throws Exception {
        var result = review(Map.of("files", List.of(file())));
        assertThat(result.path("scanComplete").asBoolean()).isTrue();
        assertThat(result.path("productionPolicyVerified").asBoolean()).isFalse();
        assertThat(result.path("llmExecuted").asBoolean()).isFalse();
        assertThat(result.path("ruleVersions").size()).isEqualTo(14);
        assertThat(result.path("findings")).isNotEmpty();
        assertThat(result.toString()).contains("OFFLINE_OBSERVE_BASELINE", "OFFLINE_BUILTIN_RULES_DIFF_ONLY")
            .doesNotContain("Thread.sleep", "patch", "rawEvidence");
    }

    @Test
    void honorsOnlyExplicitLocalPolicyAndChecksDetectorVersion() throws Exception {
        var baseline = review(Map.of("files", List.of(file())));
        String id = baseline.path("findings").get(0).path("ruleId").asText();
        String version = "";
        for (var rule : baseline.path("ruleVersions")) {
            if (rule.path("id").asText().equals(id)) version = rule.path("detectorVersion").asText();
        }
        var policy = new ReviewRuleSettings(id, "ENABLED", "*", "HIGH", 90, EnforcementMode.OBSERVE,
            "", "", "fixture", version, 1, 1);
        var result = review(Map.of("files", List.of(file()), "policy", List.of(policy)));
        assertThat(result.path("policySource").asText()).isEqualTo("LOCAL_EXPLICIT_SNAPSHOT");
        assertThat(result.path("findings").get(0).path("severity").asText()).isEqualTo("HIGH");
        assertThat(result.path("ruleVersions").size()).isEqualTo(1);
        assertThatThrownBy(() -> review(Map.of("files", List.of(file()), "policy", List.of(policy, policy))))
            .hasMessageContaining("unsupported_or_duplicate_policy_rule");
        var wrongVersion = new ReviewRuleSettings(id, "ENABLED", "*", "HIGH", 90, EnforcementMode.OBSERVE,
            "", "", "fixture", "wrong-version", 1, 1);
        assertThatThrownBy(() -> review(Map.of("files", List.of(file()), "policy", List.of(wrongVersion))))
            .hasMessageContaining("unsupported_or_duplicate_policy_rule");
    }

    @Test
    void fullContextResolvesAuthorizationOutsideTheDiff() throws Exception {
        String patch = "@@ -20,0 +21,2 @@\n+ @PostMapping(\"/users\")\n+ void create() {}\n";
        String content = "@RequireRole(\"ADMIN\")\n@RestController\nclass AdminController {\n"
            + " // padding\n".repeat(17) + " @PostMapping(\"/users\")\n void create() {}\n}\n";
        var file = new java.util.HashMap<String, Object>(Map.of("filename", "src/main/java/example/AdminController.java", "patch", patch));
        var before = review(Map.of("files", List.of(file)));
        assertThat(before.path("findings").toString()).contains("RG-AUTH-001");
        file.put("context", Map.of("status", "AVAILABLE", "content", content));
        var after = review(Map.of("files", List.of(file)));
        assertThat(after.path("findings").toString()).doesNotContain("RG-AUTH-001");
        assertThat(after.path("scope").asText()).isEqualTo("OFFLINE_BUILTIN_RULES_WITH_LOCAL_CONTEXT");
        assertThat(after.toString()).doesNotContain("padding", "RequireRole");
    }

    @Test
    void deletedAndUnavailableContextsDoNotPretendToHaveSource() throws Exception {
        for (String status : List.of("DELETED", "UNAVAILABLE")) {
            var result = review(Map.of("files", List.of(Map.of("filename", "gone.java", "patch", "",
                "context", Map.of("status", status, "content", "Thread.sleep(200);")))));
            assertThat(result.path("findings")).isEmpty();
        }
    }

    @Test
    void rejectsInvalidAndOversizedInputsBeforeRuleExecution() {
        assertThatThrownBy(() -> LocalReviewCommand.review(new byte[3 * 1024 * 1024 + 1]))
            .hasMessage("input_budget_exceeded");
        assertThatThrownBy(() -> LocalReviewCommand.review("{}".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> review(Map.of("files", "invalid"))).hasMessage("invalid_files");
        assertThatThrownBy(() -> review(Map.of("files", java.util.Collections.nCopies(201, file())))).hasMessage("invalid_files");
        assertThatThrownBy(() -> review(Map.of("files", List.of(Map.of("filename", " ", "patch", "")))))
            .hasMessage("file_budget_exceeded");
        assertThatThrownBy(() -> review(Map.of("files", List.of(Map.of("filename", "a.java", "patch", "x".repeat(256 * 1024 + 1))))))
            .hasMessage("file_budget_exceeded");
        assertThatThrownBy(() -> review(Map.of("files", List.of(), "policy", List.of())))
            .hasMessage("invalid_policy");
    }

    @Test
    void rejectsUntrustedContextStatusAndMultibyteOversize() {
        for (var context : List.of(Map.of("status", "NOT_REQUESTED", "content", ""),
            Map.of("status", "AVAILABLE", "content", "中".repeat(90_000)))) {
            assertThatThrownBy(() -> review(Map.of("files", List.of(Map.of("filename", "a.java", "patch", "", "context", context)))))
                .hasMessage("invalid_local_context");
        }
    }
}
