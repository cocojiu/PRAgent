package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class LlmPullRequestReviewer implements PullRequestReviewer {

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final SecretCryptoService secretCryptoService;
    private final LlmReviewResultParser reviewResultParser;
    private final RepoGuardMetrics metrics;
    private final ExternalCallResilience resilience;

    public LlmPullRequestReviewer(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        SecretCryptoService secretCryptoService,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience
    ) {
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.ruleBasedReviewer = ruleBasedReviewer;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.secretCryptoService = secretCryptoService;
        this.metrics = metrics;
        this.resilience = resilience;
        this.reviewResultParser = new LlmReviewResultParser(objectMapper);
    }

    @Override
    public ReviewResult review(ReviewTask task, GithubPullRequestDiff diff) {
        long startedAt = System.nanoTime();
        ReviewPolicyConfig config = reviewPolicyConfigMapper.selectById(1L);
        String promptSummary = promptSummary(diff);
        if (!isLlmReady(config)) {
            return fallbackReview(diff, "LLM config is incomplete", config, startedAt, promptSummary);
        }

        try {
            String content = callLlm(config, task, diff);
            ReviewResult parsed = reviewResultParser.parse(content);
            return ReviewResult.completed(
                parsed.riskLevel(),
                parsed.findings(),
                config.getLlmProvider(),
                config.getModelName(),
                elapsedMillis(startedAt),
                "parsed",
                promptSummary
            );
        } catch (RuntimeException ex) {
            if (Boolean.TRUE.equals(config.getFallbackToRules())) {
                return fallbackReview(diff, ex.getMessage(), config, startedAt, promptSummary);
            }
            throw ex;
        }
    }

    private ReviewResult fallbackReview(
        GithubPullRequestDiff diff,
        String reason,
        ReviewPolicyConfig config,
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
            config == null ? null : config.getLlmProvider(),
            config == null ? null : config.getModelName(),
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

    private boolean isLlmReady(ReviewPolicyConfig config) {
        return config != null
            && Boolean.TRUE.equals(config.getLlmEnabled())
            && StringUtils.hasText(config.getBaseUrl())
            && StringUtils.hasText(secretCryptoService.decrypt(config.getApiKeyValue()))
            && StringUtils.hasText(config.getModelName())
            && !"mock".equalsIgnoreCase(config.getLlmProvider());
    }

    private String callLlm(ReviewPolicyConfig config, ReviewTask task, GithubPullRequestDiff diff) {
        long startedAt = System.nanoTime();
        RestClient restClient = restClientBuilder
            .baseUrl(config.getBaseUrl().trim())
            .requestFactory(requestFactory(config.getTimeoutSeconds()))
            .build();
        String apiKey = secretCryptoService.decrypt(config.getApiKeyValue());

        Map<String, Object> payload = Map.of(
            "model", config.getModelName(),
            "temperature", config.getTemperature(),
            "max_tokens", config.getMaxTokens(),
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
            return extractMessageContent(response);
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

    private String extractMessageContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            return root.at("/choices/0/message/content").asText("");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM HTTP response", ex);
        }
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

    private Integer elapsedMillis(long startedAt) {
        long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

}
