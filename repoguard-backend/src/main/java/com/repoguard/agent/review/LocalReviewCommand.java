package com.repoguard.agent.review;

import com.repoguard.agent.config.JacksonConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON stdin/stdout adapter for offline preflight. Does not start Spring, persistence, or networking. */
public final class LocalReviewCommand {
    private LocalReviewCommand() { }

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException("unexpected_arguments");
            byte[] input = System.in.readNBytes(3 * 1024 * 1024 + 1);
            System.out.write((review(input) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            System.err.println("Local preflight failed (" + error.getClass().getSimpleName() + ")");
            System.exit(2);
        }
    }

    static String review(byte[] input) throws Exception {
        if (input.length > 3 * 1024 * 1024) throw new IllegalArgumentException("input_budget_exceeded");
        var json = new JacksonConfig().objectMapper();
        var request = json.readTree(new String(input, StandardCharsets.UTF_8));
        var filesNode = request.required("files");
        if (!filesNode.isArray() || filesNode.size() > 200) throw new IllegalArgumentException("invalid_files");
        List<PullRequestChangedFile> files = new java.util.ArrayList<>();
        for (var node : filesNode) {
            String path = node.required("filename").asText();
            String patch = node.required("patch").asText();
            if (path.isBlank() || path.length() > 1024 || patch.length() > 256 * 1024) {
                throw new IllegalArgumentException("file_budget_exceeded");
            }
            ChangedFileContext context = ChangedFileContext.notRequested(path);
            if (node.has("context")) {
                var supplied = node.required("context");
                var status = ChangedFileContext.Status.valueOf(supplied.required("status").asText());
                String content = supplied.path("content").asText("");
                if (!List.of(ChangedFileContext.Status.AVAILABLE, ChangedFileContext.Status.UNAVAILABLE,
                    ChangedFileContext.Status.DELETED).contains(status)
                    || content.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
                    throw new IllegalArgumentException("invalid_local_context");
                }
                context = new ChangedFileContext(path, null,
                    status == ChangedFileContext.Status.AVAILABLE ? content : "", status, "local_snapshot");
            }
            files.add(new PullRequestChangedFile(path, node.path("status").asText("modified"), 0, 0, patch, context));
        }
        RuleMatchFactory factory = new RuleMatchFactory();
        List<ReviewRule> lineRules = List.of(new SensitiveLiteralRule(factory), new SensitiveLoggingRule(factory),
            new BroadExceptionCatchRule(factory), new FixedSleepRule(factory), new TodoCommentRule(factory),
            new TaskStatusStringRule(factory), new StandardOutputLoggingRule(factory), new RawExternalCallRule(factory),
            new RabbitMessagePublishRule(factory), new GithubCommentDirectPublishRule(factory),
            new DestructiveMigrationRule(factory), new RequiredColumnWithoutDefaultRule(factory),
            new ControllerAuthorizationGuardRule(factory));
        ReviewRuleRegistry registry = new ReviewRuleRegistry(lineRules, List.of(new ControllerApiTestCoverageRule(factory)));
        Map<String, ReviewRuleSettings> settings = new LinkedHashMap<>();
        for (String id : registry.ruleIds().stream().sorted().toList()) {
            settings.put(id, new ReviewRuleSettings(id, "ENABLED", "*", "MEDIUM", 80,
                EnforcementMode.OBSERVE, "", "", "Offline built-in baseline", registry.detectorVersion(id), 1, 1));
        }
        boolean customPolicy = request.has("policy");
        if (customPolicy) {
            var policy = request.get("policy");
            if (!policy.isArray() || policy.size() > 100 || policy.isEmpty()) {
                throw new IllegalArgumentException("invalid_policy");
            }
            settings.clear();
            for (var entry : policy) {
                ReviewRuleSettings configured = json.treeToValue(entry, ReviewRuleSettings.class);
                if (!registry.contains(configured.id()) || configured.isDeclarative()
                    || !registry.detectorVersion(configured.id()).equals(configured.detectorVersion())
                    || settings.putIfAbsent(configured.id(), configured) != null) {
                    throw new IllegalArgumentException("unsupported_or_duplicate_policy_rule");
                }
            }
        }
        Map<String, ReviewRuleSettings> immutable = Map.copyOf(settings);
        var reviewer = new RuleBasedPullRequestReviewer(() -> immutable, registry,
            new FindingPolicyResolver(), new ReviewFindingFactory(), new ReviewFindingSemanticDeduplicator(),
            new ServerRiskAggregator(), new DeclarativeRuleMatcher(new DeclarativeRulePolicy()));
        ReviewResult result = reviewer.review(new PullRequestDiff("local", "local", 0, files),
            ReviewDeadline.startingNow(Duration.ofSeconds(10)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", files.stream().anyMatch(f -> f.context().status() != ChangedFileContext.Status.NOT_REQUESTED)
            ? "OFFLINE_BUILTIN_RULES_WITH_LOCAL_CONTEXT" : "OFFLINE_BUILTIN_RULES_DIFF_ONLY");
        response.put("policySource", customPolicy ? "LOCAL_EXPLICIT_SNAPSHOT" : "OFFLINE_OBSERVE_BASELINE");
        response.put("productionPolicyVerified", false);
        response.put("llmExecuted", false);
        response.put("ruleVersions", settings.values().stream().map(s -> Map.of("id", s.id(),
            "detectorVersion", s.detectorVersion(), "configVersion", s.configVersion(),
            "policyVersion", s.policyVersion())).toList());
        response.put("scanComplete", result.statusDetail() == null || !result.statusDetail().contains(ReviewBudgetExceededException.CATEGORY));
        response.put("findings", result.findings().stream().map(f -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ruleId", f.ruleId()); value.put("filePath", f.filePath());
            value.put("lineNumber", f.lineNumber()); value.put("severity", f.severity());
            value.put("message", f.message()); value.put("recommendation", f.recommendation());
            return value; // Exclude source excerpts, raw evidence, prompts and provider payloads.
        }).toList());
        return json.writeValueAsString(response);
    }
}
