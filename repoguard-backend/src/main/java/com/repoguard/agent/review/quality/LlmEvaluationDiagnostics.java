package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmEvaluationRunDto.Diagnostics;
import com.repoguard.agent.dto.LlmEvaluationRunDto.SampleDiagnostic;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Bounded diagnostic metadata only: never serialize evaluation cases or provider payloads. */
final class LlmEvaluationDiagnostics {
    private LlmEvaluationDiagnostics() { }

    static List<String> validate(List<String> ids) {
        if (ids == null) return null;
        if (ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(
            id -> id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "诊断样本 ID 必须为 1 至 100 个有效标识");
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    static Diagnostics initialize(List<String> ids) {
        return ids == null ? null : new Diagnostics(ids, ids.stream()
            .map(id -> new SampleDiagnostic(id, "QUEUED", null, 0, null, "UNKNOWN", "UNKNOWN")).toList());
    }

    static List<LlmEvaluationDatasetLoader.EvaluationCase> select(
        LlmEvaluationDatasetLoader.Dataset dataset, Diagnostics diagnostics
    ) {
        if (diagnostics == null) return dataset.cases();
        if (!dataset.metadata().authorized()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "诊断数据集未经授权");
        }
        Map<String, LlmEvaluationDatasetLoader.EvaluationCase> indexed;
        try {
            indexed = dataset.cases().stream().collect(Collectors.toMap(
                LlmEvaluationDatasetLoader.EvaluationCase::caseId, Function.identity()));
        } catch (IllegalStateException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "数据集包含重复样本 ID");
        }
        if (!indexed.keySet().containsAll(diagnostics.sampleIds())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "所选样本不属于当前受控数据集");
        }
        return diagnostics.sampleIds().stream().map(indexed::get).toList();
    }

    static boolean successful(LlmEvaluationObservation observation) {
        return !observation.parseFailed() && !observation.transportFailed()
            && observation.failureCategories().isBlank();
    }

    static Diagnostics update(Diagnostics value, String id, String status, String failure,
        LlmEvaluationObservation observation) {
        if (value == null) return null;
        SampleDiagnostic next = new SampleDiagnostic(id, status, failure,
            observation == null ? 0 : observation.totalTokens(),
            observation == null || observation.estimatedCost().signum() <= 0 ? null : observation.estimatedCost(),
            observation != null && observation.totalTokens() > 0 ? "RECORDED_USAGE" : "UNKNOWN",
            observation != null && observation.estimatedCost().signum() > 0 ? "ESTIMATED" : "UNKNOWN");
        return new Diagnostics(value.sampleIds(), value.samples().stream()
            .map(sample -> sample.sampleId().equals(id) ? next : sample).toList());
    }

    static Diagnostics terminal(Diagnostics value, String status, String failure) {
        if (value == null || "QUEUED".equals(status) || "RUNNING".equals(status)) return value;
        return new Diagnostics(value.sampleIds(), value.samples().stream().map(sample -> {
            if (!"QUEUED".equals(sample.status()) && !"RUNNING".equals(sample.status())) return sample;
            return new SampleDiagnostic(sample.sampleId(), "CANCELLED".equals(status) ? "CANCELLED" : "FAILED",
                failure == null ? "RUN_INTERRUPTED" : failure, sample.totalTokens(), sample.estimatedCost(),
                "UNKNOWN", "UNKNOWN");
        }).toList());
    }

    static String encode(ObjectMapper mapper, Diagnostics value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("诊断摘要编码失败", ex); }
    }

    static Diagnostics decode(ObjectMapper mapper, String value) {
        if (value == null) return null;
        try { return mapper.readValue(value, Diagnostics.class); }
        catch (Exception ex) { throw new IllegalStateException("诊断摘要读取失败", ex); }
    }
}
