package com.repoguard.agent.review;

import com.repoguard.agent.config.LlmReviewContextProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Converts a bounded repository snapshot into model-ready semantic slices.
 * Repository access is kept behind a review-owned port so the review package
 * does not depend on a particular source-control provider.
 */
@Component
class RepositorySemanticContextProvider {

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private final RepositorySemanticRepository repository;
    private final LlmReviewContextProperties properties;
    private final LlmSourceContextSlicer sourceSlicer;

    @Autowired
    RepositorySemanticContextProvider(
        RepositorySemanticRepository repository,
        LlmReviewContextProperties properties
    ) {
        this.repository = repository;
        this.properties = properties;
        this.sourceSlicer = new LlmSourceContextSlicer(properties);
    }

    RepositorySemanticContext load(PullRequestDiff diff) {
        if (!properties.isSemanticIndexEnabled() || diff == null
            || !StringUtils.hasText(diff.owner()) || !StringUtils.hasText(diff.repository())) {
            return RepositorySemanticContext.empty("disabled_or_missing_repository");
        }
        LinkedHashSet<String> symbols = changedSymbols(diff);
        RepositorySemanticSnapshot snapshot;
        try {
            snapshot = repository.fetch(diff, symbols);
        } catch (RuntimeException ex) {
            return degraded("semantic_context_provider_failed:" + ex.getClass().getSimpleName());
        }
        if (snapshot == null) {
            return degraded("semantic_context_snapshot_missing");
        }
        List<LlmContextSlice> slices = new ArrayList<>();
        for (RepositorySemanticFile file : snapshot.files()) {
            if (file == null || !relevant(file.path(), file.content(), symbols)) {
                continue;
            }
            PullRequestChangedFile relatedFile = new PullRequestChangedFile(
                file.path(), "modified", 0, 0, patchAt(file.content(), symbols),
                ChangedFileContext.available(file.path(), snapshot.defaultBranch(), file.content())
            );
            LlmContextSlice slice = sourceSlicer.slice(relatedFile, 100);
            if (slice != null) {
                slices.add(slice);
            }
        }
        List<LlmReviewContext.ContextLimitation> limitations = snapshot.limitations().stream()
            .filter(java.util.Objects::nonNull)
            .map(limitation -> new LlmReviewContext.ContextLimitation(
                limitation.filePath(), limitation.status(), limitation.reason()
            ))
            .toList();
        return new RepositorySemanticContext(
            snapshot.defaultBranch(),
            slices,
            limitations,
            snapshot.truncated(),
            snapshot.summary() + "; indexedFiles=" + slices.size()
        );
    }

    private LinkedHashSet<String> changedSymbols(PullRequestDiff diff) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (PullRequestChangedFile file : diff.files()) {
            if (file.context() != null && file.context().available()) {
                LlmContextSlice slice = sourceSlicer.slice(file, 1);
                if (slice != null) {
                    symbols.addAll(slice.symbols());
                }
            }
            String filename = file.filename();
            if (StringUtils.hasText(filename)) {
                String base = filename.substring(filename.replace('\\', '/').lastIndexOf('/') + 1);
                int dot = base.lastIndexOf('.');
                if (dot > 0) {
                    symbols.add(base.substring(0, dot)
                        .replaceFirst("(?:Tests?|IT|Spec)$", "")
                        .replaceFirst("Impl$", ""));
                }
            }
            if (symbols.size() >= 24) {
                break;
            }
        }
        symbols.removeIf(value -> !StringUtils.hasText(value) || value.length() < 3);
        return symbols.stream().limit(24)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean relevant(String path, String content, Set<String> symbols) {
        String normalizedPath = normalize(path);
        if (normalizedPath.endsWith(".md") || normalizedPath.endsWith(".txt")
            || normalizedPath.endsWith(".adoc")) {
            return false;
        }
        boolean configuration = normalizedPath.endsWith(".yml") || normalizedPath.endsWith(".yaml")
            || normalizedPath.endsWith(".properties") || normalizedPath.endsWith(".toml")
            || normalizedPath.endsWith(".xml") || normalizedPath.endsWith(".json")
            || normalizedPath.endsWith(".conf") || normalizedPath.endsWith(".ini")
            || normalizedPath.endsWith(".sql");
        if (configuration) {
            return true;
        }
        String normalizedContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        Set<String> tokens = symbols.stream()
            .flatMap(symbol -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(symbol),
                java.util.Arrays.stream(CAMEL_BOUNDARY.split(symbol))
            ))
            .filter(token -> token.length() >= 3)
            .map(token -> token.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        return symbols.stream().anyMatch(symbol -> containsWord(normalizedContent, symbol.toLowerCase(Locale.ROOT)))
            || tokens.stream().anyMatch(token -> normalizedPath.contains(token));
    }

    private String patchAt(String content, Set<String> symbols) {
        List<String> lines = content == null ? List.of() : content.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).toLowerCase(Locale.ROOT);
            if (symbols.stream().anyMatch(symbol -> containsWord(line, symbol.toLowerCase(Locale.ROOT)))) {
                int lineNumber = index + 1;
                return "@@ -" + lineNumber + ",0 +" + lineNumber + ",1 @@\n+" + lines.get(index);
            }
        }
        return null;
    }

    private boolean containsWord(String content, String word) {
        return StringUtils.hasText(word) && content.matches("(?s).*\\b" + Pattern.quote(word) + "\\b.*");
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private RepositorySemanticContext degraded(String reason) {
        return new RepositorySemanticContext(
            "", List.of(),
            List.of(new LlmReviewContext.ContextLimitation("[repository]", "UNAVAILABLE", reason)),
            false, "unavailable; reason=" + reason
        );
    }
}
