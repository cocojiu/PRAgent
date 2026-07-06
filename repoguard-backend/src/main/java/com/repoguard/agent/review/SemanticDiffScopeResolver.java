package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;

class SemanticDiffScopeResolver {

    private final SemanticDiffPathClassifier pathClassifier;
    private final SemanticDiffScopeExtractor scopeExtractor;

    SemanticDiffScopeResolver() {
        this(new SemanticDiffPathClassifier(), new SemanticDiffScopeExtractor());
    }

    SemanticDiffScopeResolver(SemanticDiffPathClassifier pathClassifier) {
        this(pathClassifier, new SemanticDiffScopeExtractor());
    }

    SemanticDiffScopeResolver(SemanticDiffPathClassifier pathClassifier, SemanticDiffScopeExtractor scopeExtractor) {
        this.pathClassifier = pathClassifier == null ? new SemanticDiffPathClassifier() : pathClassifier;
        this.scopeExtractor = scopeExtractor == null ? new SemanticDiffScopeExtractor() : scopeExtractor;
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
