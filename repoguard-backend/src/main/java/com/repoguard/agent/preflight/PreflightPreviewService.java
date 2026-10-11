package com.repoguard.agent.preflight;

import com.repoguard.agent.review.RepositoryPolicyRuntime;
import com.repoguard.agent.review.RepositoryPolicyEvaluationService;
import com.repoguard.agent.review.RuleBasedPullRequestReviewer;
import com.repoguard.agent.review.LlmPullRequestReviewer;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.LlmEvaluationBudget;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.PullRequestChangedFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.common.SensitiveTextSanitizer;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.security.PreflightCredentialService;
import com.repoguard.agent.security.PreflightCredentialService.Grant;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Server-configured preview without task persistence, release assignment or publication. */
@Service
public class PreflightPreviewService {
    private final RepositoryPolicyRuntime policies;
    private final RepositoryPolicyEvaluationService evaluator;
    private final RuleBasedPullRequestReviewer rules;
    private final com.repoguard.agent.review.ReviewRuleProvider ruleProvider;
    private final LlmPullRequestReviewer llm;
    private final PreflightPreviewStore store;
    private final PreflightCredentialService credentials;
    private final TenantProperties tenants;
    private final TenantRepositoryResolver repositories;
    private final GithubIntegrationProvider integrations;
    private final ObjectMapper json;
    private final boolean llmEnabled;
    private final Semaphore capacity = new Semaphore(1);

    public PreflightPreviewService(RepositoryPolicyRuntime policies, RepositoryPolicyEvaluationService evaluator,
        RuleBasedPullRequestReviewer rules, LlmPullRequestReviewer llm, PreflightPreviewStore store,
        PreflightCredentialService credentials, TenantProperties tenants, TenantRepositoryResolver repositories,
        GithubIntegrationProvider integrations, ObjectMapper json,
        @Value("${repoguard.preflight.llm-enabled:false}") boolean llmEnabled,
        com.repoguard.agent.review.ReviewRuleProvider ruleProvider) {
        this.policies = policies; this.evaluator = evaluator; this.rules = rules; this.llm = llm;
        this.store = store; this.credentials = credentials; this.tenants = tenants;
        this.repositories = repositories; this.integrations = integrations; this.json = json;
        this.llmEnabled = llmEnabled; this.ruleProvider = ruleProvider;
    }

    public PreflightCredentialService.Issue issue(AuthenticatedPrincipal principal, String repository, boolean useLlm) {
        requireRepository(repository, TenantContext.currentTenantIdOrDefault());
        if (useLlm && !llmEnabled) throw forbidden("Model preflight is disabled");
        return credentials.issue(principal, repository, useLlm);
    }

    private void requireRepository(String repository, long tenant) {
        if (!PreflightCredentialService.validRepository(repository)) throw badRequest();
        String[] parts = repository.split("/");
        if (tenants.isEnabled()) {
            if (!repositories.resolve(parts[0], parts[1], null).tenantId().equals(tenant))
                throw forbidden("Preflight repository belongs to another tenant");
        } else {
            var settings = integrations.getSettings();
            if (!settings.exists() || !parts[0].equalsIgnoreCase(settings.defaultOwner())
                || !parts[1].equalsIgnoreCase(settings.defaultRepo()))
                throw forbidden("Personal preflight requires the configured repository");
        }
    }

    public Map<String, Object> preview(Grant grant, byte[] input, String idempotencyKey) throws java.io.IOException {
        if (input.length > 3 * 1024 * 1024) throw badRequest();
        String sha = sha256(input);
        if (!sha.equals(idempotencyKey)) throw badRequest();
        if (!capacity.tryAcquire()) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Preflight busy");
        try (TenantContext.Scope _ = TenantContext.withTenant(grant.tenantId())) {
            JsonNode request;
            try { request = json.readTree(input); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw badRequest(); }
            PullRequestDiff diff = parse(request, grant);
            boolean useLlm = request.required("llmRequested").booleanValue();
            if (useLlm && (!llmEnabled || !grant.llmAllowed())) throw forbidden("Model preflight is not authorized");
            requireRepository(grant.repository(), grant.tenantId());
            String cached = store.claim(grant, sha, useLlm);
            if (cached != null) return json.readValue(cached,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            try {
                ReviewDeadline deadline = ReviewDeadline.startingNow(Duration.ofSeconds(30));
                var snapshot = Map.copyOf(ruleProvider.getRulesById());
                var policy = policies.preview(diff.owner(), diff.repository(), null);
                // Local HEAD is untrusted/unpushed. Only the protected server/base policy participates.
                if (!policy.warnings().isEmpty() || policy.rules().isEmpty())
                    throw new BusinessException(ErrorCode.CONFLICT, "Verified repository policy unavailable");
                deadline.requireRemaining("preflight_policy");
                ReviewResult result;
                LlmEvaluationBudget budget = null;
                if (useLlm) {
                    var settings = policy.effectiveSettings();
                    if (!settings.readyForLlmReview() || settings.inputTokenPricePerMillion() == null
                        || settings.outputTokenPricePerMillion() == null
                        || settings.inputTokenPricePerMillion().signum() < 0
                        || settings.outputTokenPricePerMillion().signum() < 0)
                        throw forbidden("Verified model settings and prices required");
                    BigDecimal cost = policy.costBudget() == null ? new BigDecimal("0.10")
                        : policy.costBudget().min(new BigDecimal("0.10"));
                    budget = new LlmEvaluationBudget(32_000, cost);
                    ReviewTask task = new ReviewTask();
                    task.setOrganization(diff.owner());
                    task.setRepository(diff.repository()); task.setCommitSha(diff.headSha());
                    task.setPrNumber(0);
                    result = llm.reviewForPreflight(task, diff, deadline, settings, budget);
                } else {
                    result = rules.review(diff, deadline, snapshot);
                }
                if (!snapshot.equals(ruleProvider.getRulesById()))
                    throw new BusinessException(ErrorCode.CONFLICT, "Server rules changed during preview");
                if (budget != null) budget.requireAvailable();
                result = evaluator.applyFindings(result, policy);
                Map<String, Object> response = response(grant, sha, policy, result,
                    budget != null && budget.accountedTokens() > 0, snapshot);
                String encoded = json.writeValueAsString(response);
                if (encoded.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw badRequest();
                store.complete(grant, sha, encoded);
                return response;
            } catch (RuntimeException | java.io.IOException failure) {
                store.fail(grant, sha);
                throw failure;
            }
        } finally {
            capacity.release();
        }
    }

    private PullRequestDiff parse(JsonNode request, Grant grant) {
        if (request == null || !request.isObject() || !grant.repository().equals(text(request, "repository", 141))
            || !request.path("llmRequested").isBoolean()) throw badRequest();
        for (String field : List.of("baseSha", "mergeBaseSha", "headSha"))
            if (!text(request, field, 64).matches("[a-f0-9]{40}|[a-f0-9]{64}")) throw badRequest();
        if (request.has("policy") || request.has("context")) throw badRequest();
        JsonNode nodes = request.path("files");
        if (!nodes.isArray() || nodes.size() > 200) throw badRequest();
        List<PullRequestChangedFile> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            String path = text(node, "filename", 1024);
            if (!safePath(path) || !seen.add(path) || node.has("context")) throw badRequest();
            String status = text(node, "status", 16);
            if (!List.of("added", "modified", "removed", "renamed").contains(status)) throw badRequest();
            String patch = text(node, "patch", 256 * 1024);
            if (patch.getBytes(StandardCharsets.UTF_8).length > 256 * 1024
                || patch.indexOf('\0') >= 0 || patch.contains("GIT binary patch")
                || patch.contains("Binary files ") || patch.contains("Subproject commit ")
                || java.util.regex.Pattern.compile("(?m)^(?:index .* 120000|new file mode 120000|deleted file mode 120000)$")
                    .matcher(patch).find()) throw badRequest();
            String previous = node.path("previousFilename").asText(null);
            if (previous != null && !safePath(previous)) throw badRequest();
            int additions = 0, deletions = 0;
            for (String line : patch.split("\\R")) {
                if (line.startsWith("+") && !line.startsWith("+++")) additions++;
                if (line.startsWith("-") && !line.startsWith("---")) deletions++;
            }
            files.add(new PullRequestChangedFile(path, status, additions, deletions, patch));
        }
        String[] repo = grant.repository().split("/");
        return new PullRequestDiff(repo[0], repo[1], 0, request.required("headSha").textValue(), files);
    }

    private Map<String, Object> response(Grant grant, String sha,
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation policy, ReviewResult result, boolean executed,
        Map<String, com.repoguard.agent.review.ReviewRuleSettings> snapshot)
        throws java.io.IOException {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (var finding : result.findings()) {
            if (findings.size() >= 2000) throw badRequest();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("severity", finding.severity()); item.put("ruleId", clean(finding.ruleId(), 128));
            item.put("filePath", clean(finding.filePath(), 1024)); item.put("lineNumber", finding.lineNumber());
            item.put("message", clean(finding.message(), 4096));
            item.put("recommendation", clean(finding.recommendation(), 4096));
            item.put("fingerprint", sha256((finding.ruleId() + ":" + finding.filePath() + ":" + finding.lineNumber())
                .getBytes(StandardCharsets.UTF_8)));
            findings.add(item);
        }
        var settings = policy.effectiveSettings();
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("rules", new java.util.TreeMap<>(policy.rules()));
        provenance.put("detectors", new java.util.TreeMap<>(snapshot));
        provenance.put("execution", result.executionProvenance());
        provenance.put("base", policy.basePolicy()); provenance.put("suppressions", policy.suppressions());
        provenance.put("strategy", settings.strategyRelease()); provenance.put("provider", settings.llmProvider());
        provenance.put("model", settings.modelName()); provenance.put("maxTokens", settings.maxTokens());
        provenance.put("cost", policy.costBudget());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", "ONLINE_PREFLIGHT"); response.put("repository", grant.repository());
        response.put("productionPolicyVerified", true);
        response.put("policyFingerprint", sha256(json.writeValueAsBytes(provenance)));
        response.put("requestSha256", sha); response.put("llmExecuted", executed);
        response.put("scanComplete", com.repoguard.agent.review.LlmStatus.COMPLETED == com.repoguard.agent.review.LlmStatus.from(result.llmStatus())
            && (result.llmParseStatus() == null || com.repoguard.agent.review.LlmParseStatus.PARSED.is(result.llmParseStatus())));
        response.put("findings", findings);
        return response;
    }

    private static String text(JsonNode node, String field, int limit) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > limit) throw badRequest();
        return value.textValue();
    }

    private static boolean safePath(String path) {
        return !path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.contains(":")
            && path.chars().noneMatch(c -> c < 32 || c == 127)
            && java.util.Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }

    private static String clean(String value, int limit) {
        String safe = SensitiveTextSanitizer.sanitize(value == null ? "" : value)
            .replaceAll("rgpf\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[REDACTED PREFLIGHT]");
        return safe.substring(0, Math.min(safe.length(), limit));
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static BusinessException badRequest() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "Invalid or oversized preflight request");
    }

    private static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message);
    }
}
