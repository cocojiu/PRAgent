package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewFindingTraceDto;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import com.repoguard.agent.review.FindingFeedbackStatus;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskDetailFindingAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CATEGORY_FINDING = "FINDING";
    private static final String CATEGORY_MISSING_TEST = "MISSING_TEST";

    private final ReviewFindingMapper reviewFindingMapper;

    public ReviewTaskDetailFindingAssembler() {
        this.reviewFindingMapper = null;
    }

    @Autowired
    public ReviewTaskDetailFindingAssembler(ReviewFindingMapper reviewFindingMapper) {
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper must not be null");
    }

    public List<ChangedFileDto> toChangedFileDtos(List<ChangedFile> changedFiles) {
        return changedFiles.stream().map(this::toChangedFileDto).toList();
    }

    public List<ReviewFindingDto> toFindingDtos(List<ReviewFinding> findings) {
        Map<Long, String> sarifBatchStatuses = sarifBatchStatuses(findings);
        return findings.stream()
            .filter(finding -> CATEGORY_FINDING.equals(finding.getCategory()))
            .map(finding -> toFindingDto(
                finding,
                finding.getSourceBatchId() == null ? null : sarifBatchStatuses.get(finding.getSourceBatchId())
            ))
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

    private ReviewFindingDto toFindingDto(ReviewFinding finding, String sourceBatchStatus) {
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
            defaultString(finding.getPolicyReason()),
            defaultString(finding.getSource()),
            defaultString(finding.getRuleId()),
            defaultString(finding.getIssueType()),
            defaultString(finding.getPreconditions()),
            relatedFiles(finding.getRelatedFiles()),
            Boolean.TRUE.equals(finding.getBlockingCandidate()),
            defaultString(finding.getVerificationStatus()),
            trace(finding),
            finding.getSourceBatchId(),
            sourceBatchStatus
        );
    }

    private Map<Long, String> sarifBatchStatuses(List<ReviewFinding> findings) {
        if (reviewFindingMapper == null || findings == null) {
            return Map.of();
        }
        return findings.stream()
            .map(ReviewFinding::getSourceBatchId)
            .filter(Objects::nonNull)
            .distinct()
            .map(id -> new BatchStatus(id, reviewFindingMapper.selectSarifImportBatchById(id)))
            .filter(entry -> entry.batch() != null)
            .collect(Collectors.toUnmodifiableMap(
                BatchStatus::id,
                entry -> defaultString(entry.batch().getStatus())
            ));
    }

    private record BatchStatus(Long id, SarifImportBatchRow batch) {
    }

    private ReviewFindingTraceDto trace(ReviewFinding finding) {
        return new ReviewFindingTraceDto(
            defaultString(finding.getDetectorVersion()),
            finding.getRuleConfigVersion(),
            defaultString(finding.getPromptVersion()),
            defaultString(finding.getContextVersion()),
            defaultString(finding.getSchemaVersion()),
            defaultString(finding.getVerifierVersion()),
            defaultString(finding.getAggregationVersion()),
            finding.getPolicyVersion(),
            finding.getLlmProvider(),
            finding.getLlmModel(),
            defaultString(finding.getOriginalSeverity()),
            defaultString(finding.getSeverity()),
            defaultString(finding.getOriginalConfidence()),
            defaultString(finding.getConfidence()),
            defaultString(finding.getDowngradeReason()),
            defaultString(finding.getBlockReason()),
            defaultString(finding.getAnchorType())
        );
    }

    private List<String> relatedFiles(String value) {
        return StringUtils.hasText(value)
            ? value.lines().map(String::trim).filter(StringUtils::hasText).distinct().toList()
            : List.of();
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
