package com.repoguard.agent.review.codeowners;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeownersRecommendationsTest {
    @Test void matchesGithubDocumentedExamplesAndLastRuleWins() {
        var result = CodeownersRules.resolve("* @default\n*.js @js\n/docs/ @docs\ndocs/* @direct\n/apps/ @apps\n/apps/github\n",
            List.of("src/A.js", "docs/start.md", "docs/nested/a.md", "apps/main/a", "apps/github/a", "src/A.JS"));
        assertThat(result.status()).isEqualTo("RESOLVED");
        assertThat(result.paths()).extracting(CodeownersRules.Match::owners).containsExactly(
            List.of("@js"), List.of("@direct"), List.of("@docs"), List.of("@apps"), List.of(), List.of("@default"));
        assertThat(result.paths().get(1).line()).isEqualTo(4);
    }
    @Test void supportsUnrootedDirectoriesDoubleStarsQuestionMarksAndComments() {
        var r = CodeownersRules.resolve("apps/ @team\n**/logs @log\n/a/**/c?.java @java # comment\n*.go a@example.org\n",
            List.of("nested/apps/file", "nested/logs/file", "a/c1.java", "a/b/c2.java", "src/x.go", "else/x"));
        assertThat(r.paths()).extracting(CodeownersRules.Match::owners).containsExactly(
            List.of("@team"), List.of("@log"), List.of("@java"), List.of("@java"), List.of("a@example.org"), List.of());
    }
    @Test void trailingDoubleStarRequiresADescendant() {
        var r = CodeownersRules.resolve("a/** @a", List.of("a", "a/x", "a/b/c"));
        assertThat(r.paths()).extracting(CodeownersRules.Match::owners)
            .containsExactly(List.of(), List.of("@a"), List.of("@a"));
    }
    @Test void unsupportedSyntaxCannotFallBackToBroaderAssignment() {
        for (String bad : List.of("[ab] @a", "!private @a", "a\\ b @a", "../secret @a", "a***b @a", "a @bad!")) {
            var r = CodeownersRules.resolve("* @default\n" + bad, List.of("private"));
            assertThat(r.status()).isEqualTo("UNSUPPORTED_SYNTAX");
            assertThat(r.invalidLines()).containsExactly(2);
            assertThat(r.paths()).isEmpty();
        }
    }
    @Test void rejectsUnsafePathsAndDeduplicatesSafeOnes() {
        for (String path : List.of("../x", "/x", "x/../y", "x\\y", "x//y", "x/", "x\ny")) {
            assertThat(CodeownersRules.resolve("* @a", List.of(path)).status()).isEqualTo("INVALID_PATH");
        }
        assertThat(CodeownersRules.resolve("* @a @a", List.of("x", "x")).paths())
            .containsExactly(new CodeownersRules.Match("x", "*", 1, List.of("@a")));
    }
    @Test void enforcesContentFileAndWorkBudgets() {
        assertThat(CodeownersRules.resolve("界".repeat(45000), List.of()).status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(CodeownersRules.resolve("* @a\n".repeat(2001), List.of()).status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(CodeownersRules.resolve("* @a", java.util.Collections.nCopies(201, "x")).status()).isEqualTo("BUDGET_EXCEEDED");
        String rules = ("a".repeat(240) + " @a\n").repeat(500);
        assertThat(CodeownersRules.resolve(rules, List.of("a".repeat(500))).status()).isEqualTo("MATCH_BUDGET_EXCEEDED");
        assertThat(CodeownersRules.resolve(null, List.of()).status()).isEqualTo("MISSING");
    }
    @Test void ranksMappedEligibleMembersByRiskFindingsAndCoverage() {
        var r = CodeownersRecommendations.recommend("* @general\nsecure/ @security @alias\nmissing/ @external",
            List.of(file("secure/a", "HIGH", 2), file("other", "LOW", 1), file("missing/a", "HIGH", 1)),
            Map.of("@general", List.of(1L), "@security", List.of(2L), "@alias", List.of(2L), "@external", List.of(3L)),
            Map.of(1L, "general", 2L, "security"));
        assertThat(r.candidates()).extracting(CodeownersRecommendations.Candidate::userId).containsExactly(2L, 1L);
        assertThat(r.candidates().getFirst().coveredFiles()).isEqualTo(1);
        assertThat(r.candidates().getFirst().findingCount()).isEqualTo(2);
        assertThat(r.candidates().getFirst().externalIdentities()).containsExactly("@security", "@alias");
        assertThat(r.uncoveredPaths()).containsExactly("missing/a");
        assertThat(r.unmappedIdentities()).containsExactly("@external");
    }
    @Test void capsCandidatesAndReportsPathsOmittedByCandidateBudget() {
        var r = CodeownersRecommendations.recommend("a @a\nb @b\nc @c\nd @d", List.of(
            file("a", "LOW", 0), file("b", "LOW", 0), file("c", "LOW", 0), file("d", "LOW", 0)),
            Map.of("@a", List.of(1L), "@b", List.of(2L), "@c", List.of(3L), "@d", List.of(4L)),
            Map.of(1L, "a", 2L, "b", 3L, "c", 4L, "d"));
        assertThat(r.candidates()).hasSize(3);
        assertThat(r.uncoveredPaths()).containsExactly("d");
    }
    @Test void ownerTextIsNeverUsedAsUsernameAndMissingMemberFallsBackUnassigned() {
        var changes = List.of(file("a", "HIGH", 1));
        assertThat(CodeownersRecommendations.recommend("* @alice", changes, Map.of(), Map.of(1L, "alice")).status())
            .isEqualTo("UNASSIGNED");
        assertThat(CodeownersRecommendations.recommend("* @alice", changes, Map.of("@alice", List.of(1L)), Map.of()).status())
            .isEqualTo("UNASSIGNED");
    }
    @Test void rejectsMalformedRankingInputs() {
        assertThat(CodeownersRecommendations.recommend("* @a", List.of(file("x", "FAKE", 0)), Map.of(), Map.of()).status())
            .isEqualTo("INVALID_CHANGES");
        assertThat(CodeownersRecommendations.recommend("* @a", List.of(file("x", "HIGH", -1)), Map.of(), Map.of()).status())
            .isEqualTo("INVALID_CHANGES");
        assertThat(CodeownersRecommendations.recommend("* @a", List.of(file("x", "HIGH", 0), file("x", "HIGH", 0)), Map.of(), Map.of()).status())
            .isEqualTo("INVALID_CHANGES");
        assertThat(CodeownersRecommendations.recommend("* @a", List.of(file("x", "HIGH", 0)),
            Map.of("@a", java.util.Collections.nCopies(21, 1L)), Map.of(1L, "a")).status()).isEqualTo("INVALID_MAPPING");
    }
    private static CodeownersRecommendations.ChangedPath file(String path, String risk, int findings) {
        return new CodeownersRecommendations.ChangedPath(path, risk, findings);
    }
    @org.junit.jupiter.api.Test void refusesOversizedOwnerIdentitiesInsteadOfRenderingUnboundedLabels() {
        var result = CodeownersRules.resolve("* @" + "x".repeat(255), java.util.List.of("a.java"));
        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("UNSUPPORTED_SYNTAX");
    }
    @Test void renameCoverageCountsEachPhysicalChangeOncePerCandidate() {
        var changes = List.of(file("new/a", "HIGH", 2), new CodeownersRecommendations.ChangedPath("old/a", "HIGH", 2, "new/a"));
        var result = CodeownersRecommendations.recommend("new/ @new\nold/ @old", changes,
            Map.of("@new", List.of(1L, 3L), "@old", List.of(2L, 3L)), Map.of(1L, "new", 2L, "old", 3L, "both"));
        assertThat(result.status()).isEqualTo("RECOMMENDATIONS"); assertThat(result.uncoveredPaths()).isEmpty();
        var both = result.candidates().stream().filter(candidate -> candidate.userId() == 3L).findFirst().orElseThrow();
        assertThat(both.paths()).containsExactly("new/a", "old/a"); assertThat(both.coveredFiles()).isEqualTo(1);
        assertThat(both.findingCount()).isEqualTo(2); assertThat(both.riskScore()).isEqualTo(4);
    }
    @Test void overlappingOldAndCurrentPathsRetainBothChangeRelationships() {
        var changes = List.of(file("new/a", "HIGH", 2), new CodeownersRecommendations.ChangedPath("old/a", "HIGH", 2, "new/a"), file("old/a", "LOW", 1));
        var result = CodeownersRecommendations.recommend("old/ @old", changes, Map.of("@old", List.of(1L)), Map.of(1L, "old"));
        assertThat(result.status()).isEqualTo("RECOMMENDATIONS"); assertThat(result.candidates().getFirst().coveredFiles()).isEqualTo(2);
        assertThat(result.candidates().getFirst().paths()).containsExactly("old/a"); assertThat(result.candidates().getFirst().findingCount()).isEqualTo(3);
        assertThat(result.basis().get(1).changedFiles()).containsExactly("new/a", "old/a"); assertThat(result.uncoveredPaths()).containsExactly("new/a");
    }
    @Test void rejectsContradictoryRenameMetadataAndKeepsTheUniquePathBudget() {
        assertThat(CodeownersRecommendations.recommend("* @a", List.of(file("new/a", "LOW", 1),
            new CodeownersRecommendations.ChangedPath("old/a", "HIGH", 1, "new/a")), Map.of(), Map.of()).status()).isEqualTo("INVALID_CHANGES");
        var changes = new java.util.ArrayList<CodeownersRecommendations.ChangedPath>();
        for (int n = 0; n < 101; n++) { changes.add(file("new/" + n, "LOW", 0)); changes.add(new CodeownersRecommendations.ChangedPath("old/" + n, "LOW", 0, "new/" + n)); }
        assertThat(CodeownersRecommendations.recommend("* @a", changes, Map.of(), Map.of()).status()).isEqualTo("BUDGET_EXCEEDED");
    }
}
