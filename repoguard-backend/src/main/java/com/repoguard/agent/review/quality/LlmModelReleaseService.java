package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelBudgetDto;
import com.repoguard.agent.dto.LlmModelReleaseCenterDto;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditExportDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditVerificationDto;
import com.repoguard.agent.dto.LlmModelReleaseRequest;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationObservationRequest;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationRequest;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationReportLifecycleRequest;
import com.repoguard.agent.dto.LlmModelRollbackRequest;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped model release control plane. It keeps release evidence aggregate-only and uses a
 * deterministic task-id hash for canary routing, so retries never change a task's assigned model.
 */
@Service
public class LlmModelReleaseService {
    private static final BigDecimal MIN_PRECISION = new BigDecimal("0.90");
    private static final BigDecimal MIN_RECALL = new BigDecimal("0.80");
    private static final BigDecimal MIN_ANCHOR_RATE = new BigDecimal("0.95");
    private static final BigDecimal MAX_DUPLICATE_RATE = new BigDecimal("0.05");
    private static final BigDecimal MAX_PARSE_FAILURE_RATE = new BigDecimal("0.05");
    private static final long MAX_P95_LATENCY_MS = 15_000L;
    private static final int MIN_CANARY_PERCENT = 1;
    private final LlmQualityComparisonProvider qualityComparisonProvider;
    private final LlmModelReleaseRepository releaseRepository;
    private final ObjectMapper objectMapper;
    private final LlmModelReleaseRuntimeSupport runtimeSupport;
    private final LlmModelReleaseAuditService auditService;
    private final LlmEvaluationReportLifecycleService lifecycleService;

    /** Compatibility constructor used by lightweight unit tests and legacy callers. */
    public LlmModelReleaseService(JdbcTemplate jdbcTemplate, LlmQualityComparisonProvider qualityComparisonProvider,
        LlmModelReleaseRepository releaseRepository, ObjectMapper objectMapper) {
        this(jdbcTemplate, qualityComparisonProvider, releaseRepository, objectMapper,
            new LlmEvaluationReportLifecycleService(jdbcTemplate, releaseRepository));
    }

    @Autowired
    public LlmModelReleaseService(JdbcTemplate jdbcTemplate, LlmQualityComparisonProvider qualityComparisonProvider,
        LlmModelReleaseRepository releaseRepository, ObjectMapper objectMapper,
        LlmEvaluationReportLifecycleService lifecycleService) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.qualityComparisonProvider = Objects.requireNonNull(qualityComparisonProvider, "qualityComparisonProvider");
        this.releaseRepository = Objects.requireNonNull(releaseRepository, "releaseRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.runtimeSupport = new LlmModelReleaseRuntimeSupport(jdbcTemplate, this.releaseRepository, this.objectMapper);
        this.auditService = new LlmModelReleaseAuditService(this.releaseRepository, this.objectMapper);
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
    }
    @Transactional
    public LlmModelReleaseCenterDto getCenter(Integer trendDays) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        runtimeSupport.reconcileCanaries(tenantId);
        int days = normalizeTrendDays(trendDays);
        Map<String, Object> configured = runtimeSupport.configuredModel(tenantId);
        List<LlmModelReleaseDto> releases = releaseRepository.findAll(tenantId);
        LlmModelReleaseDto active = runtimeSupport.firstState(releases, "ACTIVE");
        LlmModelReleaseDto canary = runtimeSupport.firstState(releases, "CANARY");
        var quality = qualityComparisonProvider.getLlmQuality(days);
        List<LlmQualityByModelDto> comparisons = quality == null ? List.of() : quality.byModel();
        var budget = runtimeSupport.monthlyBudget(tenantId);
        return new LlmModelReleaseCenterDto(text(configured.get("llm_provider")), text(configured.get("model_name")), active, canary, releases, comparisons == null ? List.of() : List.copyOf(comparisons), budget, recommendedAction(active, canary, budget));
    }

    @Transactional
    public LlmModelReleaseDto.EvaluationReportDto createEvaluationReport(LlmEvaluationRequest request, String operator) {
        EvaluationInput input = normalizeEvaluation(request);
        return createEvaluationReport(input.version(), input.dataset(), input.observations(), input.minimumSamples(), operator);
    }
    /**
     * Persists an evaluation report produced by the server-side dataset runner. The runner passes
     * only aggregate observations here; source files and prompts never enter the release store.
     */
    @Transactional
    public LlmModelReleaseDto.EvaluationReportDto createEvaluationReport(
        LlmEvaluationVersion version,
        LlmEvaluationDatasetMetadata dataset,
        List<LlmEvaluationObservation> observations,
        int minimumSamples,
        String operator
    ) {
        if (version == null || dataset == null || observations == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估报告输入不能为空");
        }
        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(version, dataset, List.copyOf(observations), minimumSamples);
        return toEvaluationDto(releaseRepository.insertEvaluationReport(
            TenantContext.currentTenantIdOrDefault(), reportKey(report), report,
            normalizeOperator(operator), lifecycleService.defaultRetentionDays()
        ));
    }
    @Transactional(readOnly = true)
    public LlmModelReleaseDto.EvaluationReportDto getEvaluationReport(long reportId) {
        return toEvaluationDto(releaseRepository.findEvaluationReport(TenantContext.currentTenantIdOrDefault(), reportId));
    }
    @Transactional(readOnly = true)
    public List<LlmModelReleaseDto.EvaluationReportDto> listEvaluationReports(int limit) {
        return releaseRepository.findEvaluationReports(TenantContext.currentTenantIdOrDefault(), limit).stream().map(this::toEvaluationDto).toList();
    }

    @Transactional(readOnly = true)
    public LlmModelReleaseDto.EvaluationReportComparisonDto compareEvaluationReports(long baselineReportId, long candidateReportId) {
        LlmEvaluationReport baseline = releaseRepository.findEvaluationReport(TenantContext.currentTenantIdOrDefault(), baselineReportId).report();
        LlmEvaluationReport candidate = releaseRepository.findEvaluationReport(TenantContext.currentTenantIdOrDefault(), candidateReportId).report();
        BigDecimal precisionDelta = candidate.precision().subtract(baseline.precision());
        BigDecimal recallDelta = candidate.recall().subtract(baseline.recall());
        BigDecimal anchorDelta = candidate.anchorRate().subtract(baseline.anchorRate());
        BigDecimal duplicateDelta = candidate.duplicateRate().subtract(baseline.duplicateRate());
        BigDecimal parseDelta = candidate.parseFailureRate().subtract(baseline.parseFailureRate());
        long latencyDelta = candidate.metrics().p95LatencyMs() - baseline.metrics().p95LatencyMs();
        BigDecimal costDelta = candidate.totalCost().subtract(baseline.totalCost());
        boolean improved = precisionDelta.signum() >= 0 && recallDelta.signum() >= 0
            && anchorDelta.signum() >= 0 && duplicateDelta.signum() <= 0 && parseDelta.signum() <= 0
            && latencyDelta <= 0 && costDelta.signum() <= 0;
        return new LlmModelReleaseDto.EvaluationReportComparisonDto(baselineReportId, candidateReportId, precisionDelta, recallDelta, anchorDelta, duplicateDelta, parseDelta, latencyDelta, costDelta, improved, candidate.blockers());
    }

    @Transactional(readOnly = true)
    public LlmModelReleaseDto.EvaluationExportDto exportEvaluationReport(long reportId, String format) {
        LlmModelReleaseDto.EvaluationReportDto dto = getEvaluationReport(reportId);
        return exportEvaluationReportContent(reportId, format, dto);
    }

    @Transactional
    public LlmModelReleaseDto.EvaluationExportDto exportEvaluationReport(
        long reportId, String format, String operator, String role) {
        lifecycleService.authorizeExport(reportId, format, operator, role);
        return exportEvaluationReportContent(reportId, format, getEvaluationReport(reportId));
    }

    private LlmModelReleaseDto.EvaluationExportDto exportEvaluationReportContent(
        long reportId, String format, LlmModelReleaseDto.EvaluationReportDto dto) {
        String normalizedFormat = "html".equalsIgnoreCase(format) ? "html" : "json";
        String content = "html".equals(normalizedFormat) ? htmlExport(dto) : jsonExport(dto);
        return new LlmModelReleaseDto.EvaluationExportDto(reportId, normalizedFormat, sha256(content), content);
    }

    @Transactional
    public LlmModelReleaseDto.EvaluationReportDto transitionEvaluationReport(
        long reportId, LlmEvaluationReportLifecycleRequest request, String operator, String role) {
        return toEvaluationDto(lifecycleService.transition(reportId, request, operator, role));
    }

    /** Called by the tenant scheduler; due reports remain retryable when a batch fails. */
    public int expireDueEvaluationReports() {
        return lifecycleService.expireDueReports();
    }

    @Transactional(readOnly = true)
    public com.repoguard.agent.dto.PageResponse<LlmModelReleaseAuditDto> listReleaseAudits(
        Long releaseId, String releaseKey, String operator, String action, String from, String to, int page, int pageSize) {
        return auditService.list(releaseId, releaseKey, operator, action, from, to, page, pageSize);
    }

    @Transactional(readOnly = true)
    public LlmModelReleaseAuditVerificationDto verifyReleaseAudit(long auditId) {
        return auditService.verify(auditId);
    }

    @Transactional(readOnly = true)
    public LlmModelReleaseAuditExportDto exportReleaseAudits(
        Long releaseId, String releaseKey, String operator, String action, String from, String to, String format) {
        return auditService.export(releaseId, releaseKey, operator, action, from, to, format);
    }

    @Transactional
    public LlmModelReleaseDto registerShadow(LlmModelReleaseRequest request, String operator) {
        // Shadow releases are evidence registrations, not a second path for client-supplied
        // quality metrics.  Validate the transport shape first so malformed fingerprints keep a
        // useful error, then replace every quality/version field with the immutable report value.
        NormalizedRelease input = normalize(request, operator);
        LlmModelReleaseRepository.StoredEvaluationReport evidence = trustedEvaluation(request);
        if (input.trafficPercent() != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "影子发布流量必须为 0");
        }
        NormalizedRelease normalized = normalizeTrusted(request, operator, evidence, 0);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        releaseRepository.lockTenant(tenantId);
        LlmModelReleaseDto before = releaseRepository.findByReleaseKey(tenantId, normalized.releaseKey());
        if (before != null && ("ACTIVE".equalsIgnoreCase(before.state()) || "CANARY".equalsIgnoreCase(before.state()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前发布已进入运行态，不能降级为影子发布");
        }
        LlmModelReleaseDto after = save(normalized, "SHADOW", 0, normalized.operator(), "");
        runtimeSupport.audit(tenantId, "REGISTER_SHADOW", before, after, normalized.operator(), "");
        return after;
    }

    @Transactional
    public LlmModelReleaseDto promote(LlmModelReleaseRequest request, String operator) {
        LlmModelReleaseRepository.StoredEvaluationReport evidence = trustedEvaluation(request);
        int trafficPercent = traffic(request.trafficPercent(), MIN_CANARY_PERCENT);
        NormalizedRelease normalized = normalizeTrusted(request, operator, evidence, trafficPercent);
        List<String> blockers = promotionBlockers(normalized);
        if (!blockers.isEmpty()) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布未通过质量门禁: " + String.join(", ", blockers));
        trafficPercent = normalized.trafficPercent();
        String state = trafficPercent == 100 ? "ACTIVE" : "CANARY";
        long tenantId = TenantContext.currentTenantIdOrDefault();
        releaseRepository.lockTenant(tenantId);
        LlmModelReleaseDto before = releaseRepository.findByReleaseKey(tenantId, normalized.releaseKey());
        if (before != null && ("ACTIVE".equalsIgnoreCase(before.state()) || "ROLLED_BACK".equalsIgnoreCase(before.state()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该发布已完成状态转换，不能重复发布");
        }
        if ("ACTIVE".equals(state)) {
            for (LlmModelReleaseDto active : releaseRepository.findByState(tenantId, "ACTIVE")) {
                runtimeSupport.audit(tenantId, "REPLACE_ACTIVE", active, null, normalized.operator(), "被新版本替换");
            }
            releaseRepository.markActiveReplaced(tenantId);
        }
        LlmModelReleaseDto after = save(normalized, state, trafficPercent, normalized.operator(), "");
        runtimeSupport.audit(tenantId, "PROMOTE", before, after, normalized.operator(), "");
        return after;
    }

    @Transactional
    public LlmModelReleaseDto rollback(long releaseId, LlmModelRollbackRequest request, String operator) {
        if (releaseId < 1 || request == null || request.reason() == null || request.reason().isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布回滚参数不完整");
        long tenantId = TenantContext.currentTenantIdOrDefault();
        releaseRepository.lockTenant(tenantId);
        LlmModelReleaseDto before = releaseRepository.findById(tenantId, releaseId);
        String reason = truncate(request.reason().trim(), 512);
        int updated = releaseRepository.rollback(tenantId, releaseId, reason);
        if (updated != 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布不存在或已回滚");
        LlmModelReleaseDto after = releaseRepository.findById(tenantId, releaseId);
        runtimeSupport.audit(tenantId, "ROLLBACK", before, after, normalizeOperator(operator), reason);
        return after;
    }

    /** Applies budget protection and persisted canary assignment to one review task. */
    public ReviewPolicySettings route(ReviewPolicySettings settings, ReviewTask task) {
        if (settings == null || task == null || !settings.enabled()) return settings;
        return runtimeSupport.route(TenantContext.currentTenantIdOrDefault(), settings, task);
    }

    private LlmModelReleaseDto save(
        NormalizedRelease release, String state, int trafficPercent, String operator, String rollbackReason
    ) {
        return releaseRepository.save(TenantContext.currentTenantIdOrDefault(), release, state, trafficPercent, operator, rollbackReason);
    }

    private List<String> promotionBlockers(NormalizedRelease release) {
        List<String> blockers = new ArrayList<>(release.blockers());
        if (!release.qualityGatePassed()) blockers.add("QUALITY_GATE_FAILED");
        if (release.trafficPercent() < MIN_CANARY_PERCENT) blockers.add("TRAFFIC_PERCENT_MUST_BE_1_TO_100");
        if (release.precisionRate().compareTo(MIN_PRECISION) < 0) blockers.add("PRECISION_BELOW_90");
        if (release.recallRate().compareTo(MIN_RECALL) < 0) blockers.add("RECALL_BELOW_80");
        if (release.anchorRate().compareTo(MIN_ANCHOR_RATE) < 0) blockers.add("ANCHOR_RATE_BELOW_95");
        if (release.duplicateRate().compareTo(MAX_DUPLICATE_RATE) > 0) blockers.add("DUPLICATE_RATE_ABOVE_5");
        if (release.parseFailureRate().compareTo(MAX_PARSE_FAILURE_RATE) > 0) blockers.add("PARSE_FAILURE_RATE_ABOVE_5");
        if (release.p95LatencyMs() > MAX_P95_LATENCY_MS) blockers.add("P95_LATENCY_ABOVE_15000_MS");
        return blockers.stream().distinct().toList();
    }

    private NormalizedRelease normalize(LlmModelReleaseRequest request, String operator) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布请求不能为空");
        String fingerprint = request.datasetFingerprint() == null
            ? ""
            : request.datasetFingerprint().trim().toLowerCase(Locale.ROOT);
        if (!fingerprint.matches("[0-9a-f]{64}")) throw new BusinessException(ErrorCode.BAD_REQUEST, "数据集指纹必须是 SHA-256 十六进制值");
        return new NormalizedRelease(
            requireText(request.releaseKey(), "releaseKey"), requireText(request.provider(), "provider"),
            requireText(request.modelName(), "modelName"), requireText(request.promptVersion(), "promptVersion"),
            requireText(request.contextVersion(), "contextVersion"), requireText(request.schemaVersion(), "schemaVersion"),
            requireText(request.datasetId(), "datasetId"), requireText(request.datasetVersion(), "datasetVersion"),
            fingerprint, traffic(request.trafficPercent(), 0), Boolean.TRUE.equals(request.qualityGatePassed()),
            decimal(request.precisionRate()), decimal(request.recallRate()), decimal(request.anchorRate()),
            decimal(request.duplicateRate()), decimal(request.parseFailureRate()), nonNegative(request.p95LatencyMs()),
            decimal(request.averageCost()), nonNegative(request.totalTokens()), normalizeBlockers(request.blockers()),
            normalizeOperator(operator), null
        );
    }
    private LlmModelReleaseRepository.StoredEvaluationReport trustedEvaluation(LlmModelReleaseRequest request) {
        if (request == null || request.evaluationReportId() == null || request.evaluationReportId() < 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布必须绑定服务端评估报告");
        LlmModelReleaseRepository.StoredEvaluationReport evidence = releaseRepository.findEvaluationReport(TenantContext.currentTenantIdOrDefault(), request.evaluationReportId());
        if (!"COMPLETED".equalsIgnoreCase(evidence.status())) throw new BusinessException(ErrorCode.BAD_REQUEST,
            "PROVISIONAL".equalsIgnoreCase(evidence.status()) ? "小样本验收报告不能用于模型发布" : "评估报告未完成，不能发布模型");
        if (!lifecycleService.usableForNewRelease(evidence, LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估报告已过期、冻结或撤销授权，不能作为新发布证据");
        }
        if (!matches(request, evidence.report())) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布请求与评估报告版本或数据集不一致");
        return evidence;
    }
    private boolean matches(LlmModelReleaseRequest request, LlmEvaluationReport report) {
        return equalsIgnoreCase(request.provider(), report.version().provider()) && equalsIgnoreCase(request.modelName(), report.version().model()) && equalsText(request.promptVersion(), report.version().promptVersion()) && equalsText(request.contextVersion(), report.version().contextVersion()) && equalsText(request.schemaVersion(), report.version().schemaVersion()) && equalsText(request.datasetId(), report.dataset().datasetId()) && equalsText(request.datasetVersion(), report.dataset().datasetVersion()) && equalsIgnoreCase(request.datasetFingerprint(), report.dataset().sampleFingerprint());
    }

    private NormalizedRelease normalizeTrusted(LlmModelReleaseRequest request, String operator,
        LlmModelReleaseRepository.StoredEvaluationReport evidence, int trafficPercent) {
        LlmEvaluationReport report = evidence.report();
        return new NormalizedRelease(
            requireText(request.releaseKey(), "releaseKey"), report.version().provider(), report.version().model(),
            report.version().promptVersion(), report.version().contextVersion(), report.version().schemaVersion(),
            report.dataset().datasetId(), report.dataset().datasetVersion(), report.dataset().sampleFingerprint(),
            trafficPercent, report.qualityGatePassed(), report.precision(), report.recall(),
            report.anchorRate(), report.duplicateRate(), report.parseFailureRate(), report.metrics().p95LatencyMs(),
            report.metrics().averageCostPerSample(), report.totalTokens(), normalizeBlockers(report.blockers()),
            normalizeOperator(operator), evidence.id()
        );
    }

    private EvaluationInput normalizeEvaluation(LlmEvaluationRequest request) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "评估请求不能为空");
        try {
            LlmEvaluationDatasetMetadata.DatasetKind kind = LlmEvaluationDatasetMetadata.DatasetKind.valueOf(request.datasetKind().trim().toUpperCase(Locale.ROOT));
            LlmEvaluationDatasetMetadata dataset = new LlmEvaluationDatasetMetadata(requireText(request.datasetId(), "datasetId"), requireText(request.datasetVersion(), "datasetVersion"), kind, required(request.sourceRepositoryCount(), "sourceRepositoryCount"), required(request.sampleCount(), "sampleCount"), required(request.fixedRegressionSamples(), "fixedRegressionSamples"), required(request.rollingObservationSamples(), "rollingObservationSamples"), Boolean.TRUE.equals(request.authorized()), Boolean.TRUE.equals(request.anonymized()), Boolean.TRUE.equals(request.humanReviewed()), requireFingerprint(request.sampleFingerprint()));
            LlmEvaluationVersion version = new LlmEvaluationVersion(requireText(request.provider(), "provider"), requireText(request.model(), "model"), requireText(request.promptVersion(), "promptVersion"), requireText(request.contextVersion(), "contextVersion"), requireText(request.schemaVersion(), "schemaVersion"), requireText(request.chunkPolicyVersion(), "chunkPolicyVersion"), request.temperature(), requireText(request.ruleVersion(), "ruleVersion"), requireText(request.codeRevision(), "codeRevision"), requireText(request.verifierVersion(), "verifierVersion"), requireText(request.aggregationVersion(), "aggregationVersion"));
            List<LlmEvaluationObservation> observations = (request.observations() == null ? List.<LlmEvaluationObservationRequest>of() : request.observations()).stream().map(this::observation).toList();
            int defaultMinimumSamples = kind == LlmEvaluationDatasetMetadata.DatasetKind.PROVISIONAL_REAL_PR ? 20 : 50;
            return new EvaluationInput(version, dataset, observations, request.minimumSamples() == null ? defaultMinimumSamples : request.minimumSamples());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估请求包含无效的版本、数据集或样本标签");
        }
    }

    private LlmEvaluationObservation observation(LlmEvaluationObservationRequest input) {
        if (input == null) throw new IllegalArgumentException("observation must not be null");
        LlmEvaluationObservation.EvaluationSplit split = LlmEvaluationObservation.EvaluationSplit.valueOf(requireText(input.split(), "split").toUpperCase(Locale.ROOT));
        return new LlmEvaluationObservation(input.caseId(), input.category(), input.expectedFinding(), input.expectedSeverity(), input.predictedFinding(), input.predictedSeverity(), input.anchorValid(), input.predictionKey(), input.parseSucceeded(), input.latencyMs(), input.totalTokens(), input.estimatedCost(), input.usefulComment(), input.commentPublishAttempted(), input.commentPublished(), input.commentFixed(), input.commentIgnored(), input.ruleFindingCount(), input.llmFindingCount(), input.verifiedFindingCount(), split, input.sourceRepositoryKey(), new LlmEvaluationSampleContext(input.language(), input.changedFileCount(), input.changedLineCount(), input.fileTypeGroup(), input.expectedLocationKey()));
    }

    private LlmModelReleaseDto.EvaluationReportDto toEvaluationDto(LlmModelReleaseRepository.StoredEvaluationReport stored) {
        LlmEvaluationReport report = stored.report();
        LlmEvaluationDatasetMetadata dataset = report.dataset();
        LlmEvaluationVersion version = report.version();
        return new LlmModelReleaseDto.EvaluationReportDto(
            stored.id(), stored.reportKey(), stored.status(), dataset.datasetId(), dataset.datasetVersion(), dataset.kind().name(),
            dataset.sourceRepositoryCount(), dataset.sampleCount(), dataset.fixedRegressionSamples(), dataset.rollingObservationSamples(),
            dataset.authorized(), dataset.anonymized(), dataset.humanReviewed(), dataset.sampleFingerprint(), version.provider(), version.model(),
            version.promptVersion(), version.contextVersion(), version.schemaVersion(), version.chunkPolicyVersion(), version.temperature(),
            version.ruleVersion(), version.codeRevision(), version.verifierVersion(), version.aggregationVersion(), report.expectedFindings(), report.predictedFindings(), report.truePositives(),
            report.falsePositives(), report.falseNegatives(), report.precision(), report.recall(), report.precisionWilsonLowerBound(),
            report.anchorRate(), report.duplicateRate(), report.parseFailureRate(), report.severityConfusion(), report.totalLatencyMs(),
            report.totalTokens(), report.totalCost(), report.blockers(), report.eligible(), metricsDto(report.metrics()), stored.createdBy(),
            stored.createdAt(), stored.lifecycleStatus(), stored.retentionDays(), stored.effectiveExpiresAt(),
            stored.authorizationRevokedAt(), stored.frozenAt(), stored.deletedAt(), stored.lifecycleVersion()
        );
    }

    private LlmModelReleaseDto.EvaluationMetricsDto metricsDto(LlmEvaluationMetrics metrics) {
        return new LlmModelReleaseDto.EvaluationMetricsDto(metrics.labeledComments(), metrics.usefulComments(), metrics.falsePositiveComments(), metrics.publishAttempts(), metrics.publishedComments(), metrics.fixedComments(), metrics.ignoredComments(), metrics.usefulCommentRate(), metrics.falsePositiveCommentRate(), metrics.publishSuccessRate(), metrics.fixRate(), metrics.ignoredRate(), metrics.p50LatencyMs(), metrics.p95LatencyMs(), metrics.averageLatencyMs(), metrics.averageTokensPerSample(), metrics.averageCostPerSample(), metrics.ruleFindings(), metrics.llmFindings(), metrics.verifiedFindings(), metrics.ruleContributionRate(), metrics.llmContributionRate(), metrics.verifiedContributionRate());
    }

    private String jsonExport(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("评估报告导出失败", ex);
        }
    }

    private String htmlExport(LlmModelReleaseDto.EvaluationReportDto dto) {
        return "<!doctype html><meta charset=\"utf-8\"><title>RepoGuard LLM Evaluation Report</title>"
            + "<h1>RepoGuard LLM Evaluation Report</h1><pre>"
            + escapeHtml(jsonExport(dto)) + "</pre>";
    }
    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String reportKey(LlmEvaluationReport report) {
        return sha256(report.version().versionKey() + "|" + report.dataset().sampleFingerprint() + "|"
            + report.sampleFingerprint() + "|" + report.totalSamples() + "|" + report.expectedFindings() + "|"
            + report.predictedFindings() + "|" + report.truePositives() + "|" + report.falsePositives() + "|"
            + report.falseNegatives() + "|" + report.precision() + "|" + report.recall() + "|" + report.anchorRate()
            + "|" + report.duplicateRate() + "|" + report.parseFailureRate() + "|" + report.totalTokens() + "|"
            + report.totalCost() + "|" + report.blockers() + "|" + report.severityConfusion() + "|" + report.metrics());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for evaluation reports", ex);
        }
    }

    private int required(Integer value, String field) {
        if (value == null) throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不能为空");
        return value;
    }

    private String requireFingerprint(String value) {
        String fingerprint = requireText(value, "sampleFingerprint").toLowerCase(Locale.ROOT);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "样本指纹必须是 SHA-256 十六进制值");
        }
        return fingerprint;
    }

    private boolean equalsText(String left, String right) {
        return left != null && right != null && left.trim().equals(right.trim());
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private String recommendedAction(LlmModelReleaseDto active, LlmModelReleaseDto canary, LlmModelBudgetDto budget) { if (budget.exhausted()) return "BUDGET_EXHAUSTED_ROLLBACK_OR_INCREASE_LIMIT"; if (canary != null) return "OBSERVE_CANARY_QUALITY_AND_COST_BEFORE_FULL_PROMOTION"; return active == null ? "REGISTER_SHADOW_DATASET_EVALUATION" : "RUN_SHADOW_EVALUATION_FOR_NEXT_VERSION"; }
    private int normalizeTrendDays(Integer value) { return value == null ? 30 : Math.max(7, Math.min(90, value)); }
    private String requireText(String value, String field) { if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不能为空"); return value.trim(); }
    private String normalizeOperator(String value) { return truncate(value == null || value.isBlank() ? "system" : value.trim(), 128); }
    private List<String> normalizeBlockers(List<String> values) { if (values == null) return List.of(); return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).map(value -> truncate(value, 128)).distinct().limit(10).toList(); }
    private BigDecimal decimal(Object value) { if (value instanceof BigDecimal decimal) return decimal.max(BigDecimal.ZERO); if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).max(BigDecimal.ZERO); if (value == null) return BigDecimal.ZERO; try { return new BigDecimal(value.toString()).max(BigDecimal.ZERO); } catch (NumberFormatException ex) { return BigDecimal.ZERO; } }
    private long nonNegative(Long value) { return value == null ? 0L : Math.max(0L, value); }
    private String text(Object value) { return value == null ? "" : value.toString(); }
    private String truncate(String value, int maxLength) { return value.length() <= maxLength ? value : value.substring(0, maxLength); }

    private int traffic(Integer value, int minimum) {
        if (value == null || value < minimum || value > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "流量比例必须在 " + minimum + "%～100% 之间");
        }
        return value;
    }

    record NormalizedRelease(String releaseKey, String provider, String modelName, String promptVersion, String contextVersion, String schemaVersion, String datasetId, String datasetVersion, String datasetFingerprint, int trafficPercent, boolean qualityGatePassed, BigDecimal precisionRate, BigDecimal recallRate, BigDecimal anchorRate, BigDecimal duplicateRate, BigDecimal parseFailureRate, long p95LatencyMs, BigDecimal averageCost, long totalTokens, List<String> blockers, String operator, Long evaluationReportId) {
        NormalizedRelease(String releaseKey, String provider, String modelName, String promptVersion, String contextVersion, String schemaVersion, String datasetId, String datasetVersion, String datasetFingerprint, int trafficPercent, boolean qualityGatePassed, BigDecimal precisionRate, BigDecimal recallRate, BigDecimal anchorRate, BigDecimal duplicateRate, BigDecimal parseFailureRate, long p95LatencyMs, BigDecimal averageCost, long totalTokens, List<String> blockers, String operator) { this(releaseKey, provider, modelName, promptVersion, contextVersion, schemaVersion, datasetId, datasetVersion, datasetFingerprint, trafficPercent, qualityGatePassed, precisionRate, recallRate, anchorRate, duplicateRate, parseFailureRate, p95LatencyMs, averageCost, totalTokens, blockers, operator, null); }
    }

    private record EvaluationInput(LlmEvaluationVersion version, LlmEvaluationDatasetMetadata dataset, List<LlmEvaluationObservation> observations, int minimumSamples) { }
}
