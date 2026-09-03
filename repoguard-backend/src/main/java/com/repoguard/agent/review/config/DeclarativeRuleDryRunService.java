package com.repoguard.agent.review.config;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.DeclarativeRuleDryRunFile;
import com.repoguard.agent.dto.DeclarativeRuleDryRunRequest;
import com.repoguard.agent.dto.DeclarativeRuleDryRunResponse;
import com.repoguard.agent.dto.DeclarativeRuleMatchDto;
import com.repoguard.agent.review.DeclarativeRuleMatcher;
import com.repoguard.agent.review.ReviewRuleProvider;
import com.repoguard.agent.review.ReviewRuleSettings;
import com.repoguard.agent.review.RuleMatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Replays an exported historical patch through one declarative rule. */
@Service
public class DeclarativeRuleDryRunService {

    private static final int MAX_PATCH_CHARS = 2_000_000;

    private final ReviewRuleProvider ruleProvider;
    private final DeclarativeRuleMatcher matcher;

    public DeclarativeRuleDryRunService(ReviewRuleProvider ruleProvider, DeclarativeRuleMatcher matcher) {
        this.ruleProvider = Objects.requireNonNull(ruleProvider, "ruleProvider");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    public DeclarativeRuleDryRunResponse run(String ruleId, DeclarativeRuleDryRunRequest request) {
        String normalizedId = ruleId == null ? "" : ruleId.trim().toUpperCase(java.util.Locale.ROOT);
        Map<String, ReviewRuleSettings> settingsById = ruleProvider.getRulesById();
        ReviewRuleSettings settings = settingsById.get(normalizedId);
        if (settings == null || !settings.isDeclarative()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only declarative rules support dry-run: " + normalizedId);
        }
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "At least one historical patch is required");
        }
        int totalChars = request.files().stream()
            .filter(Objects::nonNull)
            .mapToInt(file -> file.patch() == null ? 0 : file.patch().length())
            .sum();
        if (totalChars > MAX_PATCH_CHARS) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "Historical patch payload is too large");
        }
        List<DeclarativeRuleMatchDto> matches = new ArrayList<>();
        Set<String> matchedFiles = new HashSet<>();
        for (DeclarativeRuleDryRunFile file : request.files()) {
            if (file != null && StringUtils.hasText(file.filePath()) && StringUtils.hasText(file.patch())) {
                scanPatch(settings, file, matches, matchedFiles);
            }
        }
        return new DeclarativeRuleDryRunResponse(
            settings.id(),
            request.taskId(),
            matchedFiles.size(),
            matches.size(),
            List.copyOf(matches)
        );
    }

    private void scanPatch(
        ReviewRuleSettings settings,
        DeclarativeRuleDryRunFile file,
        List<DeclarativeRuleMatchDto> matches,
        Set<String> matchedFiles
    ) {
        int currentLine = 0;
        for (String rawLine : file.patch().split("\\R", -1)) {
            if (rawLine.startsWith("@@")) {
                currentLine = parseNewFileStart(rawLine);
                continue;
            }
            if (rawLine.startsWith("+") && !rawLine.startsWith("+++")) {
                currentLine++;
                addMatches(settings, file.filePath(), currentLine, rawLine.substring(1), matches, matchedFiles);
            } else if (!rawLine.startsWith("-")) {
                currentLine++;
            }
        }
    }

    private void addMatches(
        ReviewRuleSettings settings,
        String filePath,
        int lineNumber,
        String line,
        List<DeclarativeRuleMatchDto> matches,
        Set<String> matchedFiles
    ) {
        List<RuleMatch> lineMatches = matcher.matches(settings, filePath, lineNumber, line);
        if (!lineMatches.isEmpty()) {
            matchedFiles.add(filePath);
            lineMatches.stream()
                .map(match -> new DeclarativeRuleMatchDto(
                    match.filePath(), match.lineNumber(), match.message(), match.evidence()
                ))
                .forEach(matches::add);
        }
    }

    private int parseNewFileStart(String hunkHeader) {
        int marker = hunkHeader.indexOf('+');
        int end = hunkHeader.indexOf(' ', marker);
        if (marker < 0) {
            return 0;
        }
        String range = (end < 0 ? hunkHeader.substring(marker + 1) : hunkHeader.substring(marker + 1, end)).trim();
        int comma = range.indexOf(',');
        try {
            return Integer.parseInt(comma < 0 ? range : range.substring(0, comma)) - 1;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
