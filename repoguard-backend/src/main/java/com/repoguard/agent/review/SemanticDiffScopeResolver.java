package com.repoguard.agent.review;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class SemanticDiffScopeResolver {

    private final SemanticDiffPathClassifier pathClassifier;
    private final SemanticDiffScopeExtractor scopeExtractor;

    SemanticDiffScopeResolver(SemanticDiffPathClassifier pathClassifier, SemanticDiffScopeExtractor scopeExtractor) {
        this.pathClassifier = Objects.requireNonNull(pathClassifier, "pathClassifier");
        this.scopeExtractor = Objects.requireNonNull(scopeExtractor, "scopeExtractor");
    }

    String semanticKey(PullRequestChangedFile file, String patch) {
        String path = pathClassifier.normalizedPath(file);
        String scope = scopeExtractor.scope(path, patch, pathClassifier);
        return pathClassifier.semanticDomain(path) + ":" + pathClassifier.moduleKey(path) + ":" + scope;
    }

    String chunkGroupKey(PullRequestChangedFile file) {
        String path = pathClassifier.normalizedPath(file);
        return pathClassifier.semanticDomain(path) + ":" + pathClassifier.moduleKey(path);
    }

    String semanticReason(PullRequestChangedFile file, String patch) {
        return pathClassifier.semanticReason(pathClassifier.normalizedPath(file));
    }
}
