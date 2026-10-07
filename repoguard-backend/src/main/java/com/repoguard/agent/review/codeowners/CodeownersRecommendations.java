package com.repoguard.agent.review.codeowners;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure ranking core. Mappings and eligible members must come from the server's scoped authority. */
public final class CodeownersRecommendations {
    private CodeownersRecommendations() { }
    public record ChangedPath(String path, String risk, int findingCount, String changedFile) {
        public ChangedPath(String path, String risk, int findingCount) { this(path, risk, findingCount, path); }
    }
    public record PathEvidence(String path, String pattern, int line, List<String> owners, List<String> changedFiles) { }
    public record Candidate(Long userId, String username, int coveredFiles, int findingCount,
                            int riskScore, List<String> externalIdentities, List<String> paths) { }
    public record Result(String status, List<Candidate> candidates, List<PathEvidence> basis,
                         List<String> uncoveredPaths, List<String> unmappedIdentities) { }

    public static Result recommend(String content, List<ChangedPath> changes,
                                   Map<String, List<Long>> controlledMapping, Map<Long, String> eligibleMembers) {
        if (changes == null || changes.size() > 400 || controlledMapping == null || controlledMapping.size() > 1000
            || eligibleMembers == null || eligibleMembers.size() > 3000) return empty("BUDGET_EXCEEDED");
        Map<String, Map<String, ChangedPath>> files = new LinkedHashMap<>();
        Map<String, ChangedPath> changesByFile = new LinkedHashMap<>();
        for (ChangedPath change : changes) {
            if (change == null || change.findingCount() < 0 || change.findingCount() > 1000 || risk(change.risk()) < 0
                || change.changedFile() == null || files.computeIfAbsent(change.path(), key -> new LinkedHashMap<>())
                    .putIfAbsent(change.changedFile(), change) != null) return empty("INVALID_CHANGES");
            var previous = changesByFile.putIfAbsent(change.changedFile(), change);
            if (previous != null && (!previous.risk().equals(change.risk()) || previous.findingCount() != change.findingCount()))
                return empty("INVALID_CHANGES");
        }
        if (files.size() > 200 || changesByFile.size() > 200) return empty("BUDGET_EXCEEDED");
        if (!files.keySet().containsAll(changesByFile.keySet())) return empty("INVALID_CHANGES");
        var resolved = CodeownersRules.resolve(content, new ArrayList<>(files.keySet()));
        if (!resolved.status().equals("RESOLVED")) return empty(resolved.status());
        Map<Long, Accumulator> candidates = new LinkedHashMap<>();
        Set<String> unmapped = new LinkedHashSet<>();
        for (var match : resolved.paths()) {
            Set<Long> matched = new LinkedHashSet<>();
            for (String identity : match.owners()) {
                List<Long> mapped = controlledMapping.getOrDefault(identity, List.of());
                if (mapped == null || mapped.size() > 20) return empty("INVALID_MAPPING");
                boolean found = false;
                for (Long userId : mapped) {
                    if (userId == null || userId < 1) continue;
                    String username = eligibleMembers.get(userId);
                    if (username == null || username.isBlank() || username.length() > 128) continue;
                    found = true;
                    Accumulator candidate = candidates.computeIfAbsent(userId, id -> new Accumulator(id, username));
                    candidate.identities.add(identity);
                    if (matched.add(userId)) {
                        candidate.paths.add(match.path());
                        for (ChangedPath change : files.get(match.path()).values()) if (candidate.changedFiles.add(change.changedFile())) {
                            candidate.findings += change.findingCount(); candidate.score += risk(change.risk());
                        }
                    }
                }
                if (!found) unmapped.add(identity);
            }
        }
        List<Candidate> ranked = candidates.values().stream().map(Accumulator::snapshot)
            .sorted(Comparator.comparingInt(Candidate::riskScore).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::findingCount).reversed())
                .thenComparing(Comparator.comparingInt(Candidate::coveredFiles).reversed()).thenComparing(Candidate::userId))
            .limit(3).toList();
        Set<String> recommendedPaths = new LinkedHashSet<>();
        ranked.forEach(candidate -> recommendedPaths.addAll(candidate.paths()));
        var uncovered = files.keySet().stream().filter(path -> !recommendedPaths.contains(path)).toList();
        var basis = resolved.paths().stream().map(match -> new PathEvidence(match.path(), match.pattern(), match.line(), match.owners(),
            List.copyOf(files.get(match.path()).keySet()))).toList();
        return new Result(ranked.isEmpty() ? "UNASSIGNED" : "RECOMMENDATIONS", ranked, basis, uncovered, List.copyOf(unmapped));
    }
    private static int risk(String value) {
        return value == null ? -1 : switch (value) { case "CRITICAL" -> 8; case "HIGH" -> 4;
            case "MEDIUM" -> 2; case "LOW", "INFO" -> 1; default -> -1; };
    }
    private static Result empty(String status) { return new Result(status, List.of(), List.of(), List.of(), List.of()); }
    private static final class Accumulator {
        private final Long id; private final String username;
        private final Set<String> identities = new LinkedHashSet<>(); private final Set<String> paths = new LinkedHashSet<>();
        private final Set<String> changedFiles = new LinkedHashSet<>();
        private int findings; private int score;
        private Accumulator(Long id, String username) { this.id = id; this.username = username; }
        private Candidate snapshot() { return new Candidate(id, username, changedFiles.size(), findings, score,
            List.copyOf(identities), List.copyOf(paths)); }
    }
}
