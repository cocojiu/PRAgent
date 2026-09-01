package com.repoguard.agent.review;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

record LlmReviewContext(
    List<LlmContextSlice> slices,
    String rulePolicyContext,
    List<ContextLimitation> limitations,
    boolean budgetTruncated,
    int maxTotalChars,
    int maxRelatedFiles,
    String repositoryContextSummary
) {

    LlmReviewContext {
        slices = slices == null ? List.of() : List.copyOf(slices);
        rulePolicyContext = rulePolicyContext == null ? "" : rulePolicyContext;
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        repositoryContextSummary = repositoryContextSummary == null ? "" : repositoryContextSummary;
    }

    LlmReviewContext(
        List<LlmContextSlice> slices,
        String rulePolicyContext,
        List<ContextLimitation> limitations,
        boolean budgetTruncated,
        int maxTotalChars,
        int maxRelatedFiles
    ) {
        this(
            slices,
            rulePolicyContext,
            limitations,
            budgetTruncated,
            maxTotalChars,
            maxRelatedFiles,
            ""
        );
    }

    static LlmReviewContext legacy() {
        return new LlmReviewContext(List.of(), "", List.of(), false, 24_000, 8, "");
    }

    String renderFor(PullRequestDiff diff) {
        Set<String> primaryPaths = diff == null || diff.files() == null
            ? Set.of()
            : diff.files().stream()
                .map(PullRequestChangedFile::filename)
                .filter(StringUtils::hasText)
                .map(LlmReviewContext::normalizePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<LlmContextSlice> primary = slices.stream()
            .filter(slice -> primaryPaths.contains(normalizePath(slice.filePath())))
            .sorted(sliceOrder())
            .toList();
        Set<String> primarySymbols = primary.stream()
            .flatMap(slice -> slice.symbols().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        String primaryText = primary.stream()
            .map(LlmContextSlice::numberedContent)
            .collect(Collectors.joining("\n"));

        List<LlmContextSlice> related = slices.stream()
            .filter(slice -> !primaryPaths.contains(normalizePath(slice.filePath())))
            .filter(slice -> related(slice, primarySymbols, primaryText))
            .sorted(Comparator
                .comparingInt((LlmContextSlice slice) -> relatedRank(slice.role()))
                .thenComparingInt(LlmContextSlice::riskPriority)
                .thenComparing(LlmContextSlice::filePath))
            .limit(maxRelatedFiles)
            .toList();

        StringBuilder rendered = new StringBuilder();
        append(rendered, "Context version: " + LlmReviewVersions.CONTEXT);
        if (StringUtils.hasText(repositoryContextSummary)) {
            appendWithinBudget(rendered, "[REPOSITORY_SEMANTIC_CONTEXT] " + repositoryContextSummary);
        }
        List<ContextLimitation> relevantLimitations = limitations.stream()
            .filter(limitation -> "[repository]".equalsIgnoreCase(limitation.filePath())
                || primaryPaths.contains(normalizePath(limitation.filePath())))
            .toList();
        if (!relevantLimitations.isEmpty() || budgetTruncated) {
            String values = relevantLimitations.stream()
                .map(limitation -> limitation.filePath() + "=" + limitation.status() + ":" + limitation.reason())
                .collect(Collectors.joining(", "));
            if (budgetTruncated) {
                values = values.isBlank() ? "context_budget_truncated" : values + ", context_budget_truncated";
            }
            appendWithinBudget(rendered, "[CONTEXT_LIMITATIONS] " + values);
        }
        for (LlmContextSlice slice : primary) {
            appendWithinBudget(rendered, slice.render(slice.role()));
        }
        for (LlmContextSlice slice : related) {
            LlmContextSlice.Role role = relatedRole(slice, primarySymbols, primaryText);
            appendWithinBudget(rendered, slice.render(role));
        }
        if (StringUtils.hasText(rulePolicyContext)) {
            appendWithinBudget(rendered, "[ENABLED_RULE_POLICY]\n" + rulePolicyContext);
        }
        return rendered.toString();
    }

    boolean unavailableFor(String filePath) {
        String normalized = normalizePath(filePath);
        return limitations.stream().anyMatch(limitation -> normalizePath(limitation.filePath()).equals(normalized));
    }

    boolean hasSliceFor(String filePath) {
        String normalized = normalizePath(filePath);
        return slices.stream().anyMatch(slice -> normalizePath(slice.filePath()).equals(normalized));
    }

    String versionSummary() {
        return "promptVersion=" + LlmReviewVersions.PROMPT
            + "; contextVersion=" + LlmReviewVersions.CONTEXT
            + "; schemaVersion=" + LlmReviewVersions.SCHEMA
            + "; verifierVersion=" + LlmReviewVersions.VERIFIER;
    }

    private boolean related(LlmContextSlice slice, Set<String> primarySymbols, String primaryText) {
        if (slice.role() == LlmContextSlice.Role.CONFIG) {
            return true;
        }
        if (!disjointIgnoreCase(slice.symbols(), primarySymbols)) {
            return true;
        }
        return primarySymbols.stream().anyMatch(symbol -> containsSymbol(slice.numberedContent(), symbol))
            || slice.symbols().stream().anyMatch(symbol -> containsSymbol(primaryText, symbol));
    }

    private LlmContextSlice.Role relatedRole(
        LlmContextSlice slice,
        Set<String> primarySymbols,
        String primaryText
    ) {
        if (slice.role() == LlmContextSlice.Role.TEST || slice.role() == LlmContextSlice.Role.CONFIG
            || slice.role() == LlmContextSlice.Role.INTERFACE) {
            return slice.role();
        }
        if (primarySymbols.stream().anyMatch(symbol -> containsSymbol(slice.numberedContent(), symbol))
            || slice.symbols().stream().anyMatch(symbol -> containsSymbol(primaryText, symbol))) {
            return LlmContextSlice.Role.DIRECT_CALLER;
        }
        return LlmContextSlice.Role.SOURCE;
    }

    private boolean disjointIgnoreCase(Set<String> left, Set<String> right) {
        Set<String> normalized = right.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return left.stream().map(value -> value.toLowerCase(Locale.ROOT)).noneMatch(normalized::contains);
    }

    private boolean containsSymbol(String text, String symbol) {
        return StringUtils.hasText(symbol) && text != null && text.matches(
            "(?s).*\\b" + java.util.regex.Pattern.quote(symbol) + "\\b.*"
        );
    }

    private Comparator<LlmContextSlice> sliceOrder() {
        return Comparator.comparingInt(LlmContextSlice::riskPriority).thenComparing(LlmContextSlice::filePath);
    }

    private int relatedRank(LlmContextSlice.Role role) {
        return switch (role) {
            case INTERFACE -> 0;
            case DIRECT_CALLER, SOURCE -> 1;
            case TEST -> 2;
            case CONFIG -> 3;
        };
    }

    private void appendWithinBudget(StringBuilder builder, String value) {
        if (!StringUtils.hasText(value) || builder.length() >= maxTotalChars) {
            return;
        }
        int separatorChars = builder.isEmpty() ? 0 : 2;
        int remaining = maxTotalChars - builder.length() - separatorChars;
        if (remaining <= 0) {
            return;
        }
        String bounded = value.length() <= remaining ? value : value.substring(0, remaining);
        append(builder, bounded);
    }

    private void append(StringBuilder builder, String value) {
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(value);
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    record ContextLimitation(String filePath, String status, String reason) {
    }
}
