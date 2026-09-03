package com.repoguard.agent.github;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.RepositoryPolicyDocument;
import com.repoguard.agent.review.RepositoryPolicyEvaluationService;
import com.repoguard.agent.review.RepositoryPolicyParser;
import com.repoguard.agent.review.RepositoryPolicyRuntime;
import com.repoguard.agent.review.RepositorySuppressionService;
import com.repoguard.agent.review.ReviewPolicyProvider;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewRuleProvider;
import com.repoguard.agent.review.ReviewRuleRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** GitHub-backed adapter for the repository policy runtime port. */
@Service
public class GithubRepositoryPolicyRuntime implements RepositoryPolicyRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubRepositoryPolicyRuntime.class);
    private static final int MAX_CACHED_POLICIES = 512;

    private final ReviewPolicyProvider reviewPolicyProvider;
    private final ReviewRuleProvider reviewRuleProvider;
    private final ReviewRuleRegistry reviewRuleRegistry;
    private final GithubRepositoryPolicyReader policyReader;
    private final RepositoryPolicyParser parser;
    private final RepositoryPolicyEvaluationService evaluationService;
    private final RepositorySuppressionService suppressionService;
    private final ConcurrentHashMap<String, RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation> prepared =
        new ConcurrentHashMap<>();

    @Autowired
    public GithubRepositoryPolicyRuntime(
        ReviewPolicyProvider reviewPolicyProvider,
        ReviewRuleProvider reviewRuleProvider,
        ReviewRuleRegistry reviewRuleRegistry,
        GithubRepositoryPolicyReader policyReader,
        RepositoryPolicyEvaluationService evaluationService,
        ObjectProvider<RepositorySuppressionService> suppressionServiceProvider
    ) {
        this.reviewPolicyProvider = Objects.requireNonNull(reviewPolicyProvider, "reviewPolicyProvider");
        this.reviewRuleProvider = Objects.requireNonNull(reviewRuleProvider, "reviewRuleProvider");
        this.reviewRuleRegistry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry");
        this.policyReader = Objects.requireNonNull(policyReader, "policyReader");
        this.parser = new RepositoryPolicyParser(reviewRuleRegistry.ruleIds());
        this.evaluationService = Objects.requireNonNull(evaluationService, "evaluationService");
        this.suppressionService = suppressionServiceProvider == null
            ? null : suppressionServiceProvider.getIfAvailable();
    }

    public GithubRepositoryPolicyRuntime(
        ReviewPolicyProvider reviewPolicyProvider,
        ReviewRuleProvider reviewRuleProvider,
        ReviewRuleRegistry reviewRuleRegistry,
        GithubRepositoryPolicyReader policyReader,
        RepositoryPolicyEvaluationService evaluationService
    ) {
        this(reviewPolicyProvider, reviewRuleProvider, reviewRuleRegistry, policyReader, evaluationService, null);
    }

    @Override
    public ReviewPolicySettings applyLlmSettings(ReviewTask task, ReviewPolicySettings serverSettings) {
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = prepare(task, serverSettings);
        return evaluationService.applyLlmSettings(serverSettings, evaluation);
    }

    @Override
    public ReviewResult applyFindings(ReviewTask task, ReviewResult result) {
        if (result == null) {
            return null;
        }
        String key = key(task);
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = prepared.remove(key);
        if (evaluation == null) {
            evaluation = prepare(task, reviewPolicyProvider.getSettings());
        }
        return evaluationService.applyFindings(result, evaluation);
    }

    @Override
    public RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation preview(
        String organization,
        String repository,
        String headSha
    ) {
        GithubRepositoryPolicyReader.PolicySource source;
        try {
            source = policyReader.readForPreview(organization, repository, headSha);
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "Repository policy preview degraded repository={}/{} exceptionType={}",
                organization,
                repository,
                ex.getClass().getName()
            );
            source = GithubRepositoryPolicyReader.PolicySource.empty("policy_source_unavailable");
        }
        return evaluateSource(source, reviewPolicyProvider.getSettings(), null);
    }

    private RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation prepare(
        ReviewTask task,
        ReviewPolicySettings serverSettings
    ) {
        if (task == null) {
            return evaluationService.evaluate(serverSettings, Map.of(), null, null, List.of(), List.of("missing_task"));
        }
        String key = key(task);
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation existing = prepared.get(key);
        if (existing != null) {
            return existing;
        }
        GithubRepositoryPolicyReader.PolicySource source;
        try {
            source = policyReader.readForTask(task);
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "Repository policy load degraded repository={}/{} taskId={} exceptionType={}",
                task.getOrganization(),
                task.getRepository(),
                task.getId(),
                ex.getClass().getName()
            );
            source = GithubRepositoryPolicyReader.PolicySource.empty("policy_source_unavailable");
        }
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = evaluateSource(
            source,
            serverSettings == null ? reviewPolicyProvider.getSettings() : serverSettings,
            task
        );
        if (prepared.size() >= MAX_CACHED_POLICIES) {
            prepared.clear();
        }
        prepared.put(key, evaluation);
        return evaluation;
    }

    private RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluateSource(
        GithubRepositoryPolicyReader.PolicySource source,
        ReviewPolicySettings serverSettings,
        ReviewTask task
    ) {
        List<String> warnings = new ArrayList<>();
        if (source != null && source.error() != null) {
            warnings.add(source.error());
        }
        RepositoryPolicyDocument base = parseSafely(source == null ? null : source.baseContent(), warnings, "base");
        RepositoryPolicyDocument head = parseSafely(source == null ? null : source.headContent(), warnings, "head");
        Map<String, com.repoguard.agent.review.ReviewRuleSettings> rules;
        try {
            rules = reviewRuleProvider.getRulesById();
        } catch (RuntimeException ex) {
            LOGGER.warn("Repository policy rule catalog unavailable exceptionType={}", ex.getClass().getName());
            rules = Map.of();
        }
        List<RepositoryPolicyDocument.SuppressionReference> suppressions = task == null || suppressionService == null
            ? List.of()
            : suppressionService.activeReferences(task.getOrganization(), task.getRepository());
        return evaluationService.evaluate(serverSettings, rules, base, head, suppressions, warnings);
    }

    private RepositoryPolicyDocument parseSafely(String content, List<String> warnings, String source) {
        if (content == null || content.isBlank()) {
            return RepositoryPolicyDocument.empty();
        }
        try {
            return parser.parse(content);
        } catch (RepositoryPolicyParser.RepositoryPolicyException ex) {
            warnings.add("repository_policy_" + source + "_invalid");
            LOGGER.warn("Repository policy {} content rejected reason={}", source, ex.getMessage());
            return RepositoryPolicyDocument.empty();
        }
    }

    private String key(ReviewTask task) {
        if (task == null) {
            return "<none>";
        }
        if (task.getId() != null) {
            return "task:" + task.getId();
        }
        return "repo:" + safe(task.getOrganization()) + "/" + safe(task.getRepository()) + "@" + safe(task.getCommitSha());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
