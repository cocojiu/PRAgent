package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
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

    String semanticKey(GithubChangedFile file, String patch) {
        String path = pathClassifier.normalizedPath(file);
        String scope = scopeExtractor.scope(path, patch, pathClassifier);
        return pathClassifier.semanticDomain(path) + ":" + pathClassifier.moduleKey(path) + ":" + scope;
    }

    String chunkGroupKey(GithubChangedFile file) {
        String path = pathClassifier.normalizedPath(file);
        return pathClassifier.semanticDomain(path) + ":" + pathClassifier.moduleKey(path);
    }

    String semanticReason(GithubChangedFile file, String patch) {
        return pathClassifier.semanticReason(pathClassifier.normalizedPath(file));
    }
}
