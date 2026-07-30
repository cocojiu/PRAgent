package com.repoguard.agent.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class LlmReviewQualityScorer {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@.*$");

    ReviewResult score(ReviewResult review, PullRequestDiff diff) {
        if (review == null || review.findings() == null || review.findings().isEmpty()) {
            return review;
        }

        DiffLineIndex diffLineIndex = DiffLineIndex.from(diff);
        List<ReviewFindingResult> scoredFindings = new ArrayList<>();
        for (ReviewFindingResult finding : review.findings()) {
            if (!"LLM".equalsIgnoreCase(finding.source())) {
                scoredFindings.add(finding);
                continue;
            }
            scoredFindings.add(scoreFinding(finding, diffLineIndex));
        }
        return ReviewResult.completed(review.riskLevel(), scoredFindings);
    }

    private ReviewFindingResult scoreFinding(ReviewFindingResult finding, DiffLineIndex diffLineIndex) {
        String filePath = normalizePath(finding.filePath());
        boolean fileExists = diffLineIndex.containsFile(filePath);
        LineCheck lineCheck = checkLine(finding, filePath, fileExists, diffLineIndex);
        int score = qualityScore(finding, fileExists, lineCheck);
        String evidence = appendQualityEvidence(finding.evidence(), score, fileExists, lineCheck);
        Integer lineNumber = lineCheck.validAddedLine() ? finding.lineNumber() : null;

        return new ReviewFindingResult(
            finding.severity(),
            finding.source(),
            finding.ruleId(),
            finding.filePath(),
            lineNumber,
            finding.message(),
            finding.recommendation(),
            confidence(score),
            evidence,
            finding.impact(),
            finding.fixExample(),
            finding.isBlocking(),
            finding.reviewDimension()
        );
    }

    private LineCheck checkLine(
        ReviewFindingResult finding,
        String filePath,
        boolean fileExists,
        DiffLineIndex diffLineIndex
    ) {
        if (finding.lineNumber() == null) {
            return new LineCheck(false, "missing");
        }
        if (finding.lineNumber() < 1) {
            return new LineCheck(false, "invalid");
        }
        if (!fileExists) {
            return new LineCheck(false, "file_not_in_diff");
        }
        if (diffLineIndex.addedLines(filePath).contains(finding.lineNumber())) {
            return new LineCheck(true, "added_line");
        }
        return new LineCheck(false, "not_changed_line");
    }

    private int qualityScore(ReviewFindingResult finding, boolean fileExists, LineCheck lineCheck) {
        int score = 0;
        if (fileExists) {
            score += 30;
        }
        if (lineCheck.validAddedLine()) {
            score += 30;
        } else if ("missing".equals(lineCheck.reason())) {
            score += 10;
        }
        if (hasSubstantiveText(finding.message())) {
            score += 20;
        }
        if (hasSubstantiveText(finding.recommendation())) {
            score += 15;
        }
        if (StringUtils.hasText(finding.evidence())) {
            score += 5;
        }
        return Math.min(100, score);
    }

    private boolean hasSubstantiveText(String value) {
        return StringUtils.hasText(value) && value.trim().length() >= 12;
    }

    private String confidence(int score) {
        if (score >= 80) {
            return "HIGH";
        }
        if (score >= 55) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String appendQualityEvidence(String evidence, int score, boolean fileExists, LineCheck lineCheck) {
        String quality = "LLM quality score=" + score
            + "; diffFile=" + (fileExists ? "matched" : "missing")
            + "; diffLine=" + lineCheck.reason();
        if (!StringUtils.hasText(evidence)) {
            return quality;
        }
        if (evidence.contains("LLM quality score=")) {
            return evidence;
        }
        return evidence.trim() + " | " + quality;
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private record LineCheck(boolean validAddedLine, String reason) {
    }

    private static class DiffLineIndex {

        private final Map<String, Set<Integer>> addedLinesByFile;

        private DiffLineIndex(Map<String, Set<Integer>> addedLinesByFile) {
            this.addedLinesByFile = addedLinesByFile;
        }

        static DiffLineIndex from(PullRequestDiff diff) {
            Map<String, Set<Integer>> addedLinesByFile = new LinkedHashMap<>();
            List<PullRequestChangedFile> files = diff == null || diff.files() == null ? List.of() : diff.files();
            for (PullRequestChangedFile file : files) {
                addedLinesByFile.put(normalized(file.filename()), parseAddedLines(file.patch()));
            }
            return new DiffLineIndex(addedLinesByFile);
        }

        boolean containsFile(String filePath) {
            return addedLinesByFile.containsKey(filePath);
        }

        Set<Integer> addedLines(String filePath) {
            return addedLinesByFile.getOrDefault(filePath, Set.of());
        }

        private static Set<Integer> parseAddedLines(String patch) {
            Set<Integer> addedLines = new LinkedHashSet<>();
            if (!StringUtils.hasText(patch)) {
                return addedLines;
            }
            Integer newLine = null;
            for (String line : patch.split("\\R")) {
                Matcher matcher = HUNK_HEADER.matcher(line);
                if (matcher.matches()) {
                    newLine = Integer.parseInt(matcher.group(1));
                    continue;
                }
                if (newLine == null) {
                    continue;
                }
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    addedLines.add(newLine);
                    newLine++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    // Deleted lines do not advance the target line number.
                } else {
                    newLine++;
                }
            }
            return addedLines;
        }

        private static String normalized(String path) {
            return path == null ? "" : path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        }
    }
}
