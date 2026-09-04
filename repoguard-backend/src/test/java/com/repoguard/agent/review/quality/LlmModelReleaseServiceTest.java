package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.LlmModelReleaseCenterDto;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditExportDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditVerificationDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditDto;
import com.repoguard.agent.dto.LlmModelReleaseRequest;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationObservationRequest;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationRequest;
import com.repoguard.agent.dto.LlmModelRollbackRequest;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LlmModelReleaseServiceTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final LlmQualityComparisonProvider qualityProvider = org.mockito.Mockito.mock(LlmQualityComparisonProvider.class);
    private final LlmModelReleaseRepository repository = org.mockito.Mockito.mock(LlmModelReleaseRepository.class);
    private final LlmModelReleaseService service = new LlmModelReleaseService(jdbcTemplate, qualityProvider, repository, new JacksonConfig().objectMapper());
    private TenantContext.Scope tenantScope;

    @BeforeEach
    void setTenant() {
        tenantScope = TenantContext.withTenant(42L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(repository.findAll(anyLong())).thenReturn(List.of());
        when(repository.findByState(anyLong(), anyString())).thenReturn(List.of());
        when(repository.findEvaluationReport(eq(42L), anyLong())).thenReturn(evidence(true));
    }

    @AfterEach
    void clearTenant() {
        tenantScope.close();
    }

    @Test
    void rejectsNullAndMalformedDatasetRequestsBeforeWriting() {
        assertThatThrownBy(() -> service.registerShadow(null, "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请求不能为空");

        assertThatThrownBy(() -> service.registerShadow(request("not-a-fingerprint", 0, true), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("SHA-256");

        verify(repository, never()).save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void rejectsMissingDatasetFingerprintAfterNormalizingReleaseInput() {
        assertThatThrownBy(() -> service.registerShadow(request(null, 0, true, 77L), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("SHA-256");
    }

    @Test
    void promotionRequiresEvidenceAndIgnoresForgedClientMetrics() {
        LlmModelReleaseRequest withoutEvidence = request(FINGERPRINT, 0, false, null);
        assertThatThrownBy(() -> service.promote(withoutEvidence, "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("服务端评估报告");

        LlmModelReleaseRequest forged = request(FINGERPRINT, 20, false, 77L);
        LlmModelReleaseDto saved = release(7L, "CANARY", 20, "gpt-canary", true);
        when(repository.save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString())).thenReturn(saved);

        LlmModelReleaseDto result = service.promote(forged, "operator");

        assertThat(result).isEqualTo(saved);
        verify(repository).save(eq(42L), org.mockito.ArgumentMatchers.argThat(release ->
            release.evaluationReportId() == 77L
                && release.qualityGatePassed()
                && release.precisionRate().compareTo(new BigDecimal("0.95")) == 0
        ), eq("CANARY"), eq(20), eq("operator"), eq(""));
    }

    @Test
    void promotionRejectsFailedEvaluationReport() {
        when(repository.findEvaluationReport(42L, 78L)).thenReturn(evidence(false));

        assertThatThrownBy(() -> service.promote(request(FINGERPRINT, 20, true, 78L), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("评估报告未完成");
    }

    @Test
    void evaluationWorkbenchCreatesComparesAndExportsAggregateReport() {
        when(repository.insertEvaluationReport(eq(42L), anyString(), any(LlmEvaluationReport.class), eq("operator"), eq(180)))
            .thenReturn(evidence(true));

        LlmModelReleaseDto.EvaluationReportDto created = service.createEvaluationReport(evaluationRequest(), " operator ");

        assertThat(created.id()).isEqualTo(77L);
        assertThat(created.metrics().p95LatencyMs()).isZero();
        verify(repository).insertEvaluationReport(eq(42L), anyString(), any(LlmEvaluationReport.class), eq("operator"), eq(180));

        assertThat(service.getEvaluationReport(77L).reportKey()).isEqualTo("report-key");
        when(repository.findEvaluationReports(42L, 10)).thenReturn(List.of(evidence(true)));
        assertThat(service.listEvaluationReports(10)).singleElement().extracting(LlmModelReleaseDto.EvaluationReportDto::status)
            .isEqualTo("COMPLETED");

        LlmModelReleaseDto.EvaluationReportComparisonDto comparison = service.compareEvaluationReports(77L, 77L);
        assertThat(comparison.candidateImproved()).isTrue();
        assertThat(comparison.precisionDelta()).isZero();

        LlmModelReleaseDto.EvaluationExportDto json = service.exportEvaluationReport(77L, "json");
        assertThat(json.format()).isEqualTo("json");
        assertThat(json.contentSha256()).hasSize(64);
        assertThat(json.content()).contains("report-key");
        LlmModelReleaseDto.EvaluationExportDto html = service.exportEvaluationReport(77L, "HTML");
        assertThat(html.format()).isEqualTo("html");
        assertThat(html.content()).startsWith("<!doctype html>");
    }

    @Test
    void evaluationExportDefaultsUnknownFormatToJson() {
        when(repository.findEvaluationReport(42L, 77L)).thenReturn(evidence(true));

        LlmModelReleaseDto.EvaluationExportDto exported = service.exportEvaluationReport(77L, "yaml");

        assertThat(exported.format()).isEqualTo("json");
        assertThat(exported.content()).contains("report-key");
    }

    @Test
    void evaluationWorkbenchRejectsMalformedVersionAndObservation() {
        LlmEvaluationRequest malformed = new LlmEvaluationRequest(
            "dataset-1", "v1", "BAD_KIND", 2, 1, 1, 0, true, true, true, FINGERPRINT,
            "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "chunk-v1",
            new BigDecimal("0.1"), "rule-v1", "code-v1", List.of(observationRequest()), 1
        );
        assertThatThrownBy(() -> service.createEvaluationReport(malformed, "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无效");
    }

    @Test
    void evaluationWorkbenchDefaultsMissingObservationsAndMinimumSampleCount() {
        LlmEvaluationRequest request = new LlmEvaluationRequest(
            "dataset-1", "v1", "REAL_PR", 2, 1, 1, 0, true, true, true, FINGERPRINT,
            "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "chunk-v1",
            new BigDecimal("0.1"), "rule-v1", "code-v1", null, null
        );
        when(repository.insertEvaluationReport(eq(42L), anyString(), any(LlmEvaluationReport.class), eq("operator"), eq(180)))
            .thenReturn(evidence(true));

        assertThat(service.createEvaluationReport(request, "operator").id()).isEqualTo(77L);
    }

    @Test
    void registerShadowNormalizesValidRequestAndUsesShadowState() {
        LlmModelReleaseDto saved = release(16L, "SHADOW", 0, "gpt-next", true);
        when(repository.save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString())).thenReturn(saved);

        assertThat(service.registerShadow(request(FINGERPRINT, 0, true), " operator ")).isEqualTo(saved);
        verify(repository).save(eq(42L), any(), eq("SHADOW"), eq(0), eq("operator"), eq(""));
    }

    @Test
    void shadowRegistrationRequiresTrustedReportAndUsesServerMetrics() {
        assertThatThrownBy(() -> service.registerShadow(request(FINGERPRINT, 0, true, null), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("服务端评估报告");

        LlmModelReleaseDto saved = release(17L, "SHADOW", 0, "gpt-next", true);
        when(repository.save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString())).thenReturn(saved);
        service.registerShadow(request(FINGERPRINT, 0, false), "operator");

        verify(repository).save(eq(42L), org.mockito.ArgumentMatchers.argThat(release ->
            release.qualityGatePassed()
                && release.precisionRate().compareTo(new BigDecimal("0.95")) == 0
                && release.evaluationReportId() == 77L
        ), eq("SHADOW"), eq(0), eq("operator"), eq(""));
    }

    @Test
    void promotionRejectsOutOfRangeTrafficAndAlreadyPromotedState() {
        assertThatThrownBy(() -> service.promote(request(FINGERPRINT, 101, true), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("流量比例");

        when(repository.findByReleaseKey(42L, "release-1"))
            .thenReturn(release(18L, "ACTIVE", 100, "gpt-next", true));
        assertThatThrownBy(() -> service.promote(request(FINGERPRINT, 20, true), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能重复发布");
        verify(repository, never()).save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void promotionRejectsServerQualityMetricsEvenWhenClientClaimsPass() {
        when(repository.findEvaluationReport(42L, 79L)).thenReturn(badEvidence());

        assertThatThrownBy(() -> service.promote(request(FINGERPRINT, 20, true, 79L), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PRECISION_BELOW_90")
            .hasMessageContaining("P95_LATENCY_ABOVE_15000_MS");
    }

    @Test
    void promotionRejectsProvisionalEvaluationReport() {
        LlmModelReleaseRepository.StoredEvaluationReport completed = evidence(true);
        LlmModelReleaseRepository.StoredEvaluationReport provisional =
            new LlmModelReleaseRepository.StoredEvaluationReport(
                78L, "provisional-report", "PROVISIONAL", "tester", LocalDateTime.now(), completed.report()
            );
        when(repository.findEvaluationReport(42L, 78L)).thenReturn(provisional);

        assertThatThrownBy(() -> service.promote(request(FINGERPRINT, 20, true, 78L), "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("小样本验收报告不能用于模型发布");

        verify(repository, never()).save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void comparisonMarksCandidateRegression() {
        when(repository.findEvaluationReport(42L, 80L)).thenReturn(badEvidence());

        LlmModelReleaseDto.EvaluationReportComparisonDto comparison = service.compareEvaluationReports(77L, 80L);

        assertThat(comparison.candidateImproved()).isFalse();
        assertThat(comparison.precisionDelta()).isNegative();
    }

    @Test
    void routeReturnsDisabledOrNullInputWithoutDatabaseCalls() {
        assertThat(service.route(null, null)).isNull();
        ReviewPolicySettings disabled = new ReviewPolicySettings(
            true, false, "openai", "gpt", "https://llm.example", "secret", 30,
            new BigDecimal("0.1"), 1000, true, 2, 5, 500, 20, 5000,
            new BigDecimal("1"), new BigDecimal("2")
        );
        assertThat(service.route(disabled, task(3L))).isSameAs(disabled);
    }

    @Test
    void fullPromotionReplacesExistingActiveRelease() {
        LlmModelReleaseDto saved = release(8L, "ACTIVE", 100, "gpt-next", true);
        when(repository.save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString())).thenReturn(saved);

        assertThat(service.promote(request(FINGERPRINT, 100, true, 77L), "owner")).isEqualTo(saved);

        verify(repository).markActiveReplaced(42L);
        verify(repository).save(eq(42L), any(), eq("ACTIVE"), eq(100), eq("owner"), eq(""));
    }

    @Test
    void rollbackValidatesInputAndReturnsTenantScopedRecord() {
        LlmModelReleaseDto rolledBack = release(9L, "ROLLED_BACK", 0, "gpt-old", true);
        when(repository.rollback(42L, 9L, "security incident")).thenReturn(1);
        when(repository.findById(42L, 9L)).thenReturn(rolledBack);

        assertThat(service.rollback(9L, new LlmModelRollbackRequest(" security incident "), "reviewer"))
            .isEqualTo(rolledBack);

        assertThatThrownBy(() -> service.rollback(0L, new LlmModelRollbackRequest("reason"), "reviewer"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("参数不完整");
        assertThatThrownBy(() -> service.rollback(9L, new LlmModelRollbackRequest(" "), "reviewer"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rollbackRejectsMissingOrAlreadyRolledBackRecord() {
        when(repository.rollback(42L, 10L, "reason")).thenReturn(0);

        assertThatThrownBy(() -> service.rollback(10L, new LlmModelRollbackRequest("reason"), "reviewer"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在或已回滚");
    }

    @Test
    void routeDisablesLlmWhenMonthlyTokenBudgetIsExhausted() {
        when(jdbcTemplate.queryForList(contains("monthly_llm_token_budget"), any(Object[].class)))
            .thenReturn(List.of(Map.of("monthly_llm_token_budget", 100L, "monthly_llm_cost_budget", BigDecimal.ZERO)));
        when(jdbcTemplate.queryForList(contains("coalesce(sum(llm_total_tokens)"), any(Object[].class)))
            .thenReturn(List.of(Map.of("token_used", 100L, "cost_used", BigDecimal.ZERO)));

        ReviewPolicySettings routed = service.route(settings("gpt-configured"), task(1L));

        assertThat(routed.enabled()).isFalse();
        assertThat(routed.fallbackToRules()).isTrue();
        verify(repository, never()).findAll(anyLong());
    }

    @Test
    void routeUsesActiveReleaseOnlyWhenProviderMatches() {
        LlmModelReleaseDto active = release(11L, "ACTIVE", 100, "gpt-active", true);
        when(repository.findAll(42L)).thenReturn(List.of(active));

        ReviewPolicySettings routed = service.route(settings("openai"), task(2L));

        assertThat(routed.modelName()).isEqualTo("gpt-active");
        assertThat(routed.llmProvider()).isEqualTo("openai");
    }

    @Test
    void routeUsesDeterministicCanaryBucketAndFallsBackToActive() {
        LlmModelReleaseDto canary = release(12L, "CANARY", 50, "gpt-canary", true);
        LlmModelReleaseDto active = release(13L, "ACTIVE", 100, "gpt-active", true);
        when(repository.findByState(42L, "CANARY")).thenReturn(List.of());
        when(repository.findAll(42L)).thenReturn(List.of(canary, active));

        assertThat(service.route(settings("openai"), task(2L)).modelName()).isEqualTo("gpt-canary");
        assertThat(service.route(settings("openai"), task(1L)).modelName()).isEqualTo("gpt-active");
    }

    @Test
    void routePersistsAssignmentAndKeepsItAfterReleaseStateChanges() {
        LlmModelReleaseDto active = release(19L, "ACTIVE", 100, "gpt-active", true);
        when(repository.findAll(42L)).thenReturn(List.of(active));
        ReviewTask task = task(19L);

        assertThat(service.route(settings("openai"), task).modelName()).isEqualTo("gpt-active");
        assertThat(task.getLlmReleaseKey()).isEqualTo(active.releaseKey());

        LlmModelReleaseDto rolledBack = release(19L, "ROLLED_BACK", 0, "gpt-active", true);
        when(repository.findAll(42L)).thenReturn(List.of(rolledBack));
        assertThat(service.route(settings("openai"), task).modelName()).isEqualTo("gpt-active");
    }

    @Test
    void unsafeCanaryIsAutomaticallyRolledBackBeforeRouting() {
        LlmModelReleaseDto unsafe = release(14L, "CANARY", 30, "gpt-canary", false);
        when(repository.findByState(42L, "CANARY")).thenReturn(List.of(unsafe));

        service.route(settings("openai"), task(2L));

        verify(repository).rollback(42L, 14L, "自动回滚: QUALITY_GATE_FAILED");
    }

    @Test
    void centerCombinesConfiguredModelQualityComparisonAndBudgetRecommendation() {
        when(jdbcTemplate.queryForList(contains("llm_provider"), any(Object[].class)))
            .thenReturn(List.of(Map.of("llm_provider", "openai", "model_name", "gpt-configured")));
        when(qualityProvider.getLlmQuality(90)).thenReturn(new DashboardLlmQualityResponse(
            List.of(new LlmQualityByModelDto("openai / gpt", 3, "2s", "100", "$0.01", "99%", "0%", "0%", "99%", "0%")),
            List.of(), List.of()
        ));
        LlmModelReleaseDto active = release(15L, "ACTIVE", 100, "gpt-active", true);
        when(repository.findAll(42L)).thenReturn(List.of(active));

        LlmModelReleaseCenterDto center = service.getCenter(90);

        assertThat(center.configuredProvider()).isEqualTo("openai");
        assertThat(center.configuredModel()).isEqualTo("gpt-configured");
        assertThat(center.activeRelease()).isEqualTo(active);
        assertThat(center.modelComparison()).hasSize(1);
        assertThat(center.recommendedAction()).isEqualTo("RUN_SHADOW_EVALUATION_FOR_NEXT_VERSION");
    }

    @Test
    void centerClampsTrendDaysAndReportsBudgetExhaustion() {
        when(jdbcTemplate.queryForList(contains("monthly_llm_token_budget"), any(Object[].class)))
            .thenReturn(List.of(Map.of("monthly_llm_token_budget", 10L, "monthly_llm_cost_budget", BigDecimal.ZERO)));
        when(jdbcTemplate.queryForList(contains("coalesce(sum(llm_total_tokens)"), any(Object[].class)))
            .thenReturn(List.of(Map.of("token_used", 10L, "cost_used", BigDecimal.ZERO)));
        when(qualityProvider.getLlmQuality(7)).thenReturn(new DashboardLlmQualityResponse(List.of(), List.of(), List.of()));

        LlmModelReleaseCenterDto center = service.getCenter(1);

        assertThat(center.monthlyBudget().exhausted()).isTrue();
        assertThat(center.recommendedAction()).isEqualTo("BUDGET_EXHAUSTED_ROLLBACK_OR_INCREASE_LIMIT");
        verify(qualityProvider).getLlmQuality(7);
    }

    @Test
    void listsReleaseAuditsWithTenantFiltersAndComputesHashStatus() {
        String details = "{\"before\":{},\"after\":{}}";
        String hash = LlmModelReleaseAuditService.sha256("PROMOTE", 7L, "release-7", details, "reason");
        LlmModelReleaseRepository.ReleaseAudit audit = new LlmModelReleaseRepository.ReleaseAudit(
            91L, 7L, "release-7", "PROMOTE", "SHADOW", "CANARY", 25,
            "operator", "reason", details, hash, LocalDateTime.of(2026, 9, 3, 0, 0));
        when(repository.countAudits(eq(42L), any())).thenReturn(2L);
        when(repository.findAudits(eq(42L), any(), eq(0), eq(1))).thenReturn(List.of(audit));

        PageResponse<LlmModelReleaseAuditDto> page = service.listReleaseAudits(
            7L, "release-7", "operator", "promote", "2026-09-01T00:00:00", "2026-09-04T00:00:00", 1, 1);

        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.hashValid()).isTrue();
            assertThat(item.hashStatus()).isEqualTo("VALID");
            assertThat(item.detailsJson()).isEqualTo(details);
        });
        verify(repository).countAudits(eq(42L), argThat(filter ->
            filter.releaseId().equals(7L) && filter.releaseKey().equals("release-7")
                && filter.operator().equals("operator") && filter.action().equals("PROMOTE")));
    }

    @Test
    void verifiesTamperedAndMissingReleaseAuditsWithoutMutation() {
        LlmModelReleaseRepository.ReleaseAudit tampered = new LlmModelReleaseRepository.ReleaseAudit(
            92L, 7L, "release-7", "ROLLBACK", "ACTIVE", "ROLLED_BACK", 0,
            "operator", "reason", "{\"after\":{}}", "not-a-hash", LocalDateTime.now());
        when(repository.findAuditById(42L, 92L)).thenReturn(tampered);

        LlmModelReleaseAuditVerificationDto result = service.verifyReleaseAudit(92L);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("MALFORMED_HASH");
        verify(repository, never()).insertAudit(anyLong(), anyLong(), anyString(), anyString(), any(), any(), anyInt(), anyString(), anyString(), anyString(), anyString());
        when(repository.findAuditById(42L, 93L)).thenReturn(null);
        assertThatThrownBy(() -> service.verifyReleaseAudit(93L))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
    }

    @Test
    void exportsBoundedJsonAndCsvAndRejectsOverRangeOrInvalidFilters() {
        String details = "{\"after\":{\"modelName\":\"gpt-next\"}}";
        String hash = LlmModelReleaseAuditService.sha256("ROLLBACK", 7L, "release-7", details, "security, incident");
        LlmModelReleaseRepository.ReleaseAudit audit = new LlmModelReleaseRepository.ReleaseAudit(
            94L, 7L, "release-7", "ROLLBACK", "ACTIVE", "ROLLED_BACK", 0,
            "operator", "security, incident", details, hash, LocalDateTime.now());
        when(repository.countAudits(eq(42L), any())).thenReturn(1L);
        when(repository.findAudits(eq(42L), any(), eq(0), eq(1))).thenReturn(List.of(audit));

        LlmModelReleaseAuditExportDto json = service.exportReleaseAudits(
            null, null, null, null, null, null, "json");
        LlmModelReleaseAuditExportDto csv = service.exportReleaseAudits(
            null, null, null, null, null, null, "csv");

        assertThat(json.format()).isEqualTo("json");
        assertThat(json.recordCount()).isEqualTo(1L);
        assertThat(json.content()).contains("release-7", "eventHash", "calculatedHash", hash)
            .doesNotContain("modelName", "security, incident", "detailsJson", "provider", "promptVersion");
        assertThat(csv.format()).isEqualTo("csv");
        assertThat(csv.content()).contains("id,releaseId,releaseKey", "calculatedHash", hash)
            .doesNotContain("security, incident", "detailsJson", "modelName", "provider");

        when(repository.countAudits(eq(42L), any())).thenReturn(1_001L);
        assertThatThrownBy(() -> service.exportReleaseAudits(null, null, null, null, null, null, "json"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("1000");
        assertThatThrownBy(() -> service.exportReleaseAudits(null, null, null, "unknown", null, null, "json"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("动作");
        assertThatThrownBy(() -> service.exportReleaseAudits(null, null, null, null,
            "2026-09-04T00:00:00", "2026-09-03T00:00:00", "json"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("from < to");
    }

    private LlmModelReleaseRequest request(String fingerprint, int traffic, boolean qualityGatePassed) {
        return request(fingerprint, traffic, qualityGatePassed, 77L);
    }

    private LlmModelReleaseRequest request(String fingerprint, int traffic, boolean qualityGatePassed, Long reportId) {
        return new LlmModelReleaseRequest(
            "release-1", "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "dataset-1", "v1",
            fingerprint, traffic, qualityGatePassed, new BigDecimal("0.95"), new BigDecimal("0.85"),
            new BigDecimal("0.98"), new BigDecimal("0.01"), new BigDecimal("0.01"), 1000L,
            new BigDecimal("0.01"), 1000L, List.of(), reportId
        );
    }

    private LlmEvaluationRequest evaluationRequest() {
        return new LlmEvaluationRequest(
            "dataset-1", "v1", "REAL_PR", 2, 1, 1, 0, true, true, true, FINGERPRINT,
            "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "chunk-v1",
            new BigDecimal("0.1"), "rule-v1", "code-v1", List.of(observationRequest()), 1
        );
    }

    private LlmEvaluationObservationRequest observationRequest() {
        return new LlmEvaluationObservationRequest(
            "case-1", "security", true, "HIGH", true, "HIGH", true, "finding-1", true,
            1000L, 100L, new BigDecimal("0.01"), true, true, true, true, false,
            1L, 1L, 1L, "FIXED_REGRESSION", "repo-a", "java", 1, 20, "backend", "src-main"
        );
    }

    private LlmModelReleaseRepository.StoredEvaluationReport evidence(boolean eligible) {
        LlmEvaluationVersion version = new LlmEvaluationVersion(
            "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "chunk-v1",
            new BigDecimal("0.1"), "rule-v1", "code-v1"
        );
        LlmEvaluationDatasetMetadata dataset = new LlmEvaluationDatasetMetadata(
            "dataset-1", "v1", LlmEvaluationDatasetMetadata.DatasetKind.REAL_PR, 2, 50, 25, 25,
            true, true, true, FINGERPRINT
        );
        LlmEvaluationReport report = new LlmEvaluationReport(
            version, FINGERPRINT, 50, 10, 10, 10, 0, 0, new BigDecimal("0.95"), new BigDecimal("0.85"),
            new BigDecimal("0.91"), new BigDecimal("0.98"), new BigDecimal("0.01"), new BigDecimal("0.01"),
            Map.of(), 1000L, 1000L, new BigDecimal("0.01"), eligible ? List.of() : List.of("QUALITY_GATE_FAILED"),
            eligible, dataset, LlmEvaluationMetrics.empty()
        );
        return new LlmModelReleaseRepository.StoredEvaluationReport(
            77L, "report-key", eligible ? "COMPLETED" : "FAILED", "tester", LocalDateTime.now(), report
        );
    }

    private LlmModelReleaseRepository.StoredEvaluationReport badEvidence() {
        LlmEvaluationVersion version = new LlmEvaluationVersion(
            "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "chunk-v1",
            new BigDecimal("0.1"), "rule-v1", "code-v1"
        );
        LlmEvaluationDatasetMetadata dataset = new LlmEvaluationDatasetMetadata(
            "dataset-1", "v1", LlmEvaluationDatasetMetadata.DatasetKind.REAL_PR, 2, 50, 25, 25,
            true, true, true, FINGERPRINT
        );
        LlmEvaluationMetrics metrics = new LlmEvaluationMetrics(
            1, 1, 0, 1, 1, 1, 0, new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("1"),
            new BigDecimal("1"), BigDecimal.ZERO, 16000L, 16000L, new BigDecimal("16000"),
            new BigDecimal("100"), new BigDecimal("1"), 1L, 1L, 1L,
            new BigDecimal(".33"), new BigDecimal(".33"), new BigDecimal(".34")
        );
        LlmEvaluationReport report = new LlmEvaluationReport(
            version, FINGERPRINT, 50, 10, 10, 8, 2, 2, new BigDecimal("0.80"), new BigDecimal("0.70"),
            new BigDecimal("0.70"), new BigDecimal("0.50"), new BigDecimal("0.10"), new BigDecimal("0.10"),
            Map.of(), 800000L, 5000L, new BigDecimal("50"), List.of(), true, dataset, metrics
        );
        return new LlmModelReleaseRepository.StoredEvaluationReport(79L, "bad-report", "COMPLETED", "tester", LocalDateTime.now(), report);
    }

    private LlmModelReleaseDto release(long id, String state, int traffic, String model, boolean qualityGatePassed) {
        LocalDateTime now = LocalDateTime.now();
        return new LlmModelReleaseDto(
            id, "release-" + id, "openai", model, "prompt-v1", "context-v1", "schema-v1", "dataset-1", "v1",
            FINGERPRINT, state, traffic, qualityGatePassed, new BigDecimal("0.95"), new BigDecimal("0.85"),
            new BigDecimal("0.98"), new BigDecimal("0.01"), new BigDecimal("0.01"), 1000L, new BigDecimal("0.01"),
            1000L, List.of(), null, "tester", now, now
        );
    }

    private ReviewPolicySettings settings(String provider) {
        return new ReviewPolicySettings(
            true, true, provider, "gpt-configured", "https://llm.example", "secret", 30,
            new BigDecimal("0.1"), 1000, false, 2, 5, 500, 20, 5000,
            new BigDecimal("1"), new BigDecimal("2")
        );
    }

    private ReviewTask task(Long id) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setPrNumber(101);
        return task;
    }

    private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
}
