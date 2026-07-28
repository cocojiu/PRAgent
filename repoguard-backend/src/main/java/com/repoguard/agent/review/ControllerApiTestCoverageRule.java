package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class ControllerApiTestCoverageRule implements PullRequestReviewRule {

    static final String RULE_ID = "RG-API-001";

    private final ReviewFindingFactory findingFactory;

    ControllerApiTestCoverageRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public List<ReviewFindingResult> evaluate(
        GithubPullRequestDiff diff,
        Map<String, ReviewRuleSettings> configuredRules
    ) {
        if (diff.files() == null || diff.files().isEmpty() || hasTestChange(diff.files())) {
            return List.of();
        }
        return diff.files().stream()
            .filter(file -> isControllerApiChange(file)
                && ReviewRuleApplicability.isApplicable(RULE_ID, file.filename(), configuredRules))
            .findFirst()
            .map(file -> findingFactory.finding(
                "MEDIUM",
                RULE_ID,
                file.filename(),
                firstAddedLine(file.patch()),
                "Controller/API change is missing tests in the same PR",
                "Add ControllerTest, ApiContractTest, or related src/test coverage for request validation, permissions, status codes, and key response fields."
            ))
            .map(List::of)
            .orElseGet(List::of);
    }

    private boolean hasTestChange(List<GithubChangedFile> files) {
        return files.stream()
            .map(GithubChangedFile::filename)
            .map(ReviewRuleApplicability::normalizePath)
            .anyMatch(path -> path.contains("/src/test/")
                || path.endsWith("controllertest.java")
                || path.endsWith("apicontracttest.java")
                || path.endsWith("integrationtest.java"));
    }

    private boolean isControllerApiChange(GithubChangedFile file) {
        if (file == null || !ReviewRuleApplicability.normalizePath(file.filename()).endsWith("controller.java")) {
            return false;
        }
        String patch = file.patch();
        if (patch == null || patch.isBlank()) {
            return false;
        }
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++") && isControllerMapping(line.substring(1).trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isControllerMapping(String trimmed) {
        return trimmed.contains("@GetMapping") || trimmed.contains("@PostMapping") || trimmed.contains("@PutMapping")
            || trimmed.contains("@PatchMapping") || trimmed.contains("@DeleteMapping")
            || trimmed.contains("@RequestMapping");
    }

    private Integer firstAddedLine(String patch) {
        if (patch == null || patch.isBlank()) {
            return null;
        }
        int currentLine = 0;
        for (String line : patch.split("\\R")) {
            if (line.startsWith("@@")) {
                currentLine = parseNewFileStart(line);
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                return currentLine;
            }
            if (!line.startsWith("-")) {
                currentLine++;
            }
        }
        return null;
    }

    private int parseNewFileStart(String hunkHeader) {
        int marker = hunkHeader.indexOf('+');
        if (marker < 0) {
            return 0;
        }
        int end = hunkHeader.indexOf(' ', marker);
        String range = (end < 0 ? hunkHeader.substring(marker + 1) : hunkHeader.substring(marker + 1, end)).trim();
        int comma = range.indexOf(',');
        String start = comma < 0 ? range : range.substring(0, comma);
        try {
            return Integer.parseInt(start);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
