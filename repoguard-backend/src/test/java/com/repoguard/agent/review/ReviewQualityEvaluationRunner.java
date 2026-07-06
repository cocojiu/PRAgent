package com.repoguard.agent.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

final class ReviewQualityEvaluationRunner {

    private static final String DATASET = "/review-quality/evaluation-cases.json";

    private final ObjectMapper objectMapper;
    private final RuleBasedPullRequestReviewer ruleReviewer;
    private final LlmReviewResultParser resultParser;
    private final LlmRuleReviewMerger resultMerger;
    private final PullRequestDiffChunker diffChunker;

    ReviewQualityEvaluationRunner(ObjectMapper objectMapper, RuleBasedPullRequestReviewer ruleReviewer) {
        this.objectMapper = objectMapper;
        this.ruleReviewer = ruleReviewer;
        this.resultParser = new LlmReviewResultParser(objectMapper);
        this.resultMerger = new LlmRuleReviewMerger(new RiskLevelRanker());
        this.diffChunker = DiffChunkingTestFixtures.chunker();
    }

    List<EvaluationCase> loadCases() {
        try (InputStream input = ReviewQualityEvaluationRunner.class.getResourceAsStream(DATASET)) {
            if (input == null) {
                throw new IllegalStateException("Review quality evaluation dataset is missing: " + DATASET);
            }
            return objectMapper.readValue(input, new TypeReference<>() {
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load review quality evaluation dataset", ex);
        }
    }

    EvaluationOutcome evaluate(EvaluationCase evaluationCase) {
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "repoguard-evaluation",
            evaluationCase.id(),
            1,
            evaluationCase.files()
        );
        ReviewResult ruleResult = ruleReviewer.review(diff);
        ReviewResult finalResult = hasText(evaluationCase.llmResponse())
            ? resultMerger.mergeWithRuleReview(resultParser.parse(evaluationCase.llmResponse()), ruleResult)
            : ruleResult;
        List<String> chunkReasons = diffChunker.chunk(diff).stream()
            .flatMap(chunk -> chunk.reasons().stream())
            .distinct()
            .toList();
        List<ReviewFindingResult> findings = Objects.requireNonNullElse(finalResult.findings(), List.of());
        return new EvaluationOutcome(
            finalResult.riskLevel(),
            findings,
            chunkReasons
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record EvaluationCase(
        String id,
        String category,
        String description,
        List<GithubChangedFile> files,
        String llmResponse,
        ExpectedOutcome expected
    ) {
    }

    record ExpectedOutcome(
        String riskLevel,
        Integer findingCount,
        List<String> ruleIds,
        List<String> sources,
        List<String> chunkReasons
    ) {
    }

    record EvaluationOutcome(
        String riskLevel,
        List<ReviewFindingResult> findings,
        List<String> chunkReasons
    ) {
    }
}
