package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LlmPullRequestReviewer implements PullRequestReviewer {

    private final ReviewPolicyProvider reviewPolicyProvider;
    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final LlmReviewResultParser reviewResultParser;
    private final RepoGuardMetrics metrics;
    private final ExternalCallResilience resilience;
    private final PullRequestDiffChunker diffChunker;

    @Autowired
    public LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience
    ) {
        this(
            reviewPolicyProvider,
            ruleBasedReviewer,
            restClientBuilder,
            objectMapper,
            metrics,
            resilience,
            new PullRequestDiffChunker()
        );
    }

    LlmPullRequestReviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience,
        PullRequestDiffChunker diffChunker
    ) {
        this.reviewPolicyProvider = reviewPolicyProvider;
        this.ruleBasedReviewer = ruleBasedReviewer;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.resilience = resilience;
        this.diffChunker = diffChunker;
        this.reviewResultParser = new LlmReviewResultParser(objectMapper);
    }

    @Override
    public ReviewResult review(ReviewTask task, GithubPullRequestDiff diff) {
        long startedAt = System.nanoTime();
        ReviewPolicySettings settings = reviewPolicyProvider.getSettings();
        String promptSummary = promptSummary(diff);
        if (!isLlmReady(settings)) {
            return fallbackReview(diff, "LLM config is incomplete", settings, startedAt, promptSummary);
        }

        try {
            ReviewResult parsed = reviewWithOptionalChunks(settings, task, diff);
            ReviewResult ruleReview = ruleBasedReviewer.review(diff);
            ReviewResult merged = mergeWithRuleReview(parsed, ruleReview);
            return ReviewResult.completed(
                merged.riskLevel(),
                merged.findings(),
                settings.llmProvider(),
                settings.modelName(),
                elapsedMillis(startedAt),
                parsed.llmParseStatus() == null ? "parsed" : parsed.llmParseStatus(),
                hybridPromptSummary(parsed.llmPromptSummary() == null ? promptSummary : parsed.llmPromptSummary(), ruleReview, merged),
                parsed.llmPromptTokens(),
                parsed.llmCompletionTokens(),
                parsed.llmTotalTokens(),
                parsed.llmEstimatedCost()
            );
        } catch (RuntimeException ex) {
            if (Boolean.TRUE.equals(settings.fallbackToRules())) {
                return fallbackReview(diff, ex.getMessage(), settings, startedAt, promptSummary);
            }
            throw ex;
        }
    }

    private ReviewResult mergeWithRuleReview(ReviewResult llmReview, ReviewResult ruleReview) {
        if (ruleReview == null || ruleReview.findings() == null || ruleReview.findings().isEmpty()) {
            return llmReview;
        }
        List<ReviewFindingResult> findings = new java.util.ArrayList<>();
        if (llmReview.findings() != null) {
            findings.addAll(llmReview.findings());
        }
        findings.addAll(ruleReview.findings());
        return ReviewResult.completed(maxRisk(llmReview.riskLevel(), ruleReview.riskLevel()), findings);
    }

    private String hybridPromptSummary(String promptSummary, ReviewResult ruleReview, ReviewResult merged) {
        int ruleFindings = ruleReview == null || ruleReview.findings() == null ? 0 : ruleReview.findings().size();
        int mergedFindings = merged == null || merged.findings() == null ? 0 : merged.findings().size();
        return promptSummary
            + "; rulesApplied=true"
            + "; ruleFindings=" + ruleFindings
            + "; mergedFindings=" + mergedFindings;
    }

    private ReviewResult reviewWithOptionalChunks(ReviewPolicySettings settings, ReviewTask task, GithubPullRequestDiff diff) {
        List<PullRequestDiffChunk> chunks = diffChunker.chunk(diff, settings);
        if (chunks.size() == 1) {
            LlmCallResult callResult = callLlm(settings, task, diff);
            ReviewResult parsed = reviewResultParser.parse(callResult.content());
            return ReviewResult.completed(
                parsed.riskLevel(),
                parsed.findings(),
                null,
                null,
                null,
                null,
                promptSummary(diff),
                callResult.promptTokens(),
                callResult.completionTokens(),
                callResult.totalTokens(),
                estimatedCost(settings, callResult.promptTokens(), callResult.completionTokens())
            );
        }

        List<ReviewFindingResult> findings = new java.util.ArrayList<>();
        String riskLevel = "INFO";
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        int failedChunks = 0;
        for (PullRequestDiffChunk chunk : chunks) {
            try {
                LlmCallResult callResult = callLlm(settings, task, chunk.diff());
                ReviewResult parsed = reviewResultParser.parse(callResult.content());
                riskLevel = maxRisk(riskLevel, parsed.riskLevel());
                promptTokens += safeInt(callResult.promptTokens());
                completionTokens += safeInt(callResult.completionTokens());
                totalTokens += safeInt(callResult.totalTokens());
                if (parsed.findings() != null) {
                    findings.addAll(parsed.findings());
                }
            } catch (RuntimeException ex) {
                failedChunks++;
                if (metrics != null) {
                    metrics.llmFallback("chunk_partial_failure");
                }
                ReviewResult ruleReview = ruleBasedReviewer.review(chunk.diff());
                riskLevel = maxRisk(riskLevel, ruleReview.riskLevel());
                if (ruleReview.findings() != null) {
                    findings.addAll(ruleReview.findings());
                }
            }
        }
        return ReviewResult.completed(
            riskLevel,
            findings,
            null,
            null,
            null,
            failedChunks > 0 ? "partial_fallback" : null,
            chunkedPromptSummary(diff, chunks, findings.size(), riskLevel, failedChunks),
            zeroToNull(promptTokens),
            zeroToNull(completionTokens),
            zeroToNull(totalTokens),
            estimatedCost(settings, zeroToNull(promptTokens), zeroToNull(completionTokens))
        );
    }

    private ReviewResult fallbackReview(
        GithubPullRequestDiff diff,
        String reason,
        ReviewPolicySettings settings,
        long startedAt,
        String promptSummary
    ) {
        if (metrics != null) {
            metrics.llmFallback(reasonCategory(reason));
        }
        ReviewResult fallback = ruleBasedReviewer.review(diff);
        return ReviewResult.fallback(
            fallback.riskLevel(),
            normalizeReason(reason),
            fallback.findings(),
            settings == null ? null : settings.llmProvider(),
            settings == null ? null : settings.modelName(),
            elapsedMillis(startedAt),
            promptSummary
        );
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "LLM review unavailable";
        }
        return reason.replaceAll("\\s+", " ").trim();
    }

    private boolean isLlmReady(ReviewPolicySettings settings) {
        return settings != null && settings.exists() && settings.enabled() && settings.readyForLlmReview();
    }

    protected LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, GithubPullRequestDiff diff) {
        long startedAt = System.nanoTime();
        RestClient restClient = restClientBuilder
            .baseUrl(settings.baseUrl().trim())
            .requestFactory(requestFactory(settings.timeoutSeconds()))
            .build();
        String apiKey = settings.apiKey();

        Map<String, Object> payload = Map.of(
            "model", settings.modelName(),
            "temperature", settings.temperature(),
            "max_tokens", settings.maxTokens(),
            "messages", List.of(
                Map.of("role", "system", "content", "你是资深代码审查助手，只输出严格 JSON。"),
                Map.of("role", "user", "content", buildPrompt(task, diff))
            )
        );

        try {
            String response = executeLlm("chat_completions", () -> restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class));
            if (metrics != null) {
                metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "success");
            }
            return extractLlmCallResult(response);
        } catch (RuntimeException ex) {
            var classified = ExternalCallErrorClassifier.llm(ex);
            if (metrics != null) {
                metrics.externalCallFailed(classified);
                metrics.llmRequestDuration(Duration.ofNanos(System.nanoTime() - startedAt), "failed");
            }
            throw classified;
        }
    }

    private String reasonCategory(String reason) {
        String normalized = normalizeReason(reason).toLowerCase();
        int markerIndex = normalized.indexOf("category=");
        if (markerIndex >= 0) {
            int valueStart = markerIndex + "category=".length();
            int valueEnd = normalized.indexOf(' ', valueStart);
            return valueEnd < 0 ? normalized.substring(valueStart) : normalized.substring(valueStart, valueEnd);
        }
        if (normalized.contains("config")) {
            return "config_incomplete";
        }
        return "llm_unavailable";
    }

    private LlmCallResult extractLlmCallResult(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            return new LlmCallResult(
                root.at("/choices/0/message/content").asText(""),
                intValue(root.at("/usage/prompt_tokens")),
                intValue(root.at("/usage/completion_tokens")),
                intValue(root.at("/usage/total_tokens"))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM HTTP response", ex);
        }
    }

    private Integer intValue(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt() ? null : node.asInt();
    }

    private SimpleClientHttpRequestFactory requestFactory(Integer timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds == null ? 60 : timeoutSeconds));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private <T> T executeLlm(String operation, java.util.function.Supplier<T> supplier) {
        return resilience == null ? supplier.get() : resilience.llm(operation, supplier);
    }

    private String buildPrompt(ReviewTask task, GithubPullRequestDiff diff) {
        return """
            请审查下面的 GitHub PR diff，并只返回 JSON 对象：
            {
              "riskLevel": "INFO|LOW|MEDIUM|HIGH",
              "findings": [
                {
                  "severity": "LOW|MEDIUM|HIGH",
                  "filePath": "文件路径",
                  "lineNumber": 变更后的行号或 null,
                  "message": "问题描述",
                  "recommendation": "修复建议"
                }
              ]
            }
            PR: %s/%s#%d
            标题：%s
            Diff:
            %s
            """.formatted(diff.owner(), diff.repository(), diff.prNumber(), task.getTitle(), compactDiff(diff));
    }

    private String compactDiff(GithubPullRequestDiff diff) {
        StringBuilder builder = new StringBuilder();
        for (GithubChangedFile file : diff.files()) {
            builder.append("\n--- ").append(file.filename()).append('\n');
            if (file.patch() != null) {
                builder.append(file.patch(), 0, Math.min(file.patch().length(), 6000)).append('\n');
            }
            if (builder.length() > 20000) {
                builder.append("\n[diff truncated]\n");
                break;
            }
        }
        return builder.toString();
    }

    private String promptSummary(GithubPullRequestDiff diff) {
        int fileCount = diff.files() == null ? 0 : diff.files().size();
        int additions = 0;
        int deletions = 0;
        StringBuilder files = new StringBuilder();
        if (diff.files() != null) {
            for (int i = 0; i < diff.files().size(); i++) {
                GithubChangedFile file = diff.files().get(i);
                additions += file.additions() == null ? 0 : file.additions();
                deletions += file.deletions() == null ? 0 : file.deletions();
                if (i < 5) {
                    if (!files.isEmpty()) {
                        files.append(", ");
                    }
                    files.append(file.filename());
                }
            }
        }
        if (fileCount > 5) {
            files.append(", ...");
        }
        return "PR " + diff.owner() + "/" + diff.repository() + "#" + diff.prNumber()
            + "; files=" + fileCount
            + "; additions=" + additions
            + "; deletions=" + deletions
            + "; sampleFiles=" + files;
    }

    private String chunkedPromptSummary(
        GithubPullRequestDiff diff,
        List<PullRequestDiffChunk> chunks,
        int findingCount,
        String riskLevel,
        int failedChunks
    ) {
        int additions = chunks.stream().mapToInt(chunk -> chunk.additions() == null ? 0 : chunk.additions()).sum();
        int deletions = chunks.stream().mapToInt(chunk -> chunk.deletions() == null ? 0 : chunk.deletions()).sum();
        String reasons = chunks.stream()
            .flatMap(chunk -> chunk.reasons().stream())
            .distinct()
            .limit(6)
            .reduce((first, second) -> first + "," + second)
            .orElse("standard");
        return "PR " + diff.owner() + "/" + diff.repository() + "#" + diff.prNumber()
            + "; chunked=true"
            + "; chunks=" + chunks.size()
            + "; files=" + (diff.files() == null ? 0 : diff.files().size())
            + "; additions=" + additions
            + "; deletions=" + deletions
            + "; aggregateRisk=" + riskLevel
            + "; aggregateFindings=" + findingCount
            + "; failedChunks=" + failedChunks
            + "; chunkReasons=" + reasons;
    }

    private String maxRisk(String current, String candidate) {
        return riskRank(candidate) > riskRank(current) ? candidate : current;
    }

    private int riskRank(String riskLevel) {
        if (riskLevel == null) {
            return 0;
        }
        return switch (riskLevel.trim().toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private Integer elapsedMillis(long startedAt) {
        long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Integer zeroToNull(int value) {
        return value <= 0 ? null : value;
    }

    private BigDecimal estimatedCost(ReviewPolicySettings settings, Integer promptTokens, Integer completionTokens) {
        if (settings == null || promptTokens == null && completionTokens == null) {
            return null;
        }
        BigDecimal inputPrice = settings.inputTokenPricePerMillion() == null
            ? BigDecimal.ZERO
            : settings.inputTokenPricePerMillion();
        BigDecimal outputPrice = settings.outputTokenPricePerMillion() == null
            ? BigDecimal.ZERO
            : settings.outputTokenPricePerMillion();
        BigDecimal inputCost = BigDecimal.valueOf(safeInt(promptTokens)).multiply(inputPrice);
        BigDecimal outputCost = BigDecimal.valueOf(safeInt(completionTokens)).multiply(outputPrice);
        BigDecimal total = inputCost.add(outputCost).divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
        return total.compareTo(BigDecimal.ZERO) == 0 ? null : total;
    }

    protected record LlmCallResult(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
    ) {
    }

}
