package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.review.FindingFeedbackStatus;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskDetailFindingAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CATEGORY_FINDING = "FINDING";
    private static final String CATEGORY_MISSING_TEST = "MISSING_TEST";

    public List<ChangedFileDto> toChangedFileDtos(List<ChangedFile> changedFiles) {
        return changedFiles.stream().map(this::toChangedFileDto).toList();
    }

    public List<ReviewFindingDto> toFindingDtos(List<ReviewFinding> findings) {
        return findings.stream()
            .filter(finding -> CATEGORY_FINDING.equals(finding.getCategory()))
            .map(this::toFindingDto)
            .toList();
    }

    public List<MissingTestDto> toMissingTestDtos(List<ReviewFinding> findings) {
        return findings.stream()
            .filter(finding -> CATEGORY_MISSING_TEST.equals(finding.getCategory()))
            .map(this::toMissingTestDto)
            .toList();
    }

    private ChangedFileDto toChangedFileDto(ChangedFile file) {
        return new ChangedFileDto(file.getFilePath(), file.getChangeType(), file.getAdditions(), file.getDeletions());
    }

    private ReviewFindingDto toFindingDto(ReviewFinding finding) {
        boolean falsePositive = FindingFeedbackStatus.fromFinding(finding)
            == FindingFeedbackStatus.FALSE_POSITIVE;
        return new ReviewFindingDto(
            finding.getId(),
            lower(finding.getSeverity()),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            defaultString(finding.getConfidence()),
            defaultString(finding.getEvidence()),
            defaultString(finding.getImpact()),
            defaultString(finding.getFixExample()),
            !falsePositive && Boolean.TRUE.equals(finding.getIsBlocking()),
            defaultString(finding.getReviewDimension()),
            FindingFeedbackStatus.fromFinding(finding).dtoCode(),
            finding.getFeedbackNote(),
            finding.getFeedbackBy(),
            formatDateTimeOrNull(finding.getFeedbackAt()),
            defaultString(finding.getEnforcementMode()),
            defaultString(finding.getPolicyReason())
        );
    }

    private MissingTestDto toMissingTestDto(ReviewFinding finding) {
        return new MissingTestDto(
            finding.getFilePath(),
            finding.getMethodName(),
            finding.getTestType(),
            finding.getRecommendation()
        );
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String defaultString(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
