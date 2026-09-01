package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.LlmModelReleaseCenterDto;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseRequest;
import com.repoguard.agent.dto.LlmModelRollbackRequest;
import com.repoguard.agent.dto.LlmQualityByModelDto;
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
    private final LlmModelReleaseService service = new LlmModelReleaseService(jdbcTemplate, qualityProvider, repository);
    private TenantContext.Scope tenantScope;

    @BeforeEach
    void setTenant() {
        tenantScope = TenantContext.withTenant(42L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(repository.findAll(anyLong())).thenReturn(List.of());
        when(repository.findByState(anyLong(), anyString())).thenReturn(List.of());
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
    void promotionQualityGateRejectsUnsafeReleaseAndAcceptsCanaryRelease() {
        LlmModelReleaseRequest unsafe = request(FINGERPRINT, 0, false);
        assertThatThrownBy(() -> service.promote(unsafe, "operator"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("QUALITY_GATE_FAILED")
            .hasMessageContaining("TRAFFIC_PERCENT_MUST_BE_1_TO_100");

        LlmModelReleaseDto saved = release(7L, "CANARY", 20, "gpt-canary", true);
        when(repository.save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString())).thenReturn(saved);

        LlmModelReleaseDto result = service.promote(request(FINGERPRINT, 20, true), "operator");

        assertThat(result).isEqualTo(saved);
        verify(repository).save(eq(42L), any(), eq("CANARY"), eq(20), eq("operator"), eq(""));
    }

    @Test
    void fullPromotionReplacesExistingActiveRelease() {
        LlmModelReleaseDto saved = release(8L, "ACTIVE", 100, "gpt-next", true);
        when(repository.save(anyLong(), any(), anyString(), anyInt(), anyString(), anyString())).thenReturn(saved);

        assertThat(service.promote(request(FINGERPRINT, 100, true), "owner")).isEqualTo(saved);

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

    private LlmModelReleaseRequest request(String fingerprint, int traffic, boolean qualityGatePassed) {
        return new LlmModelReleaseRequest(
            "release-1", "openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "dataset-1", "v1",
            fingerprint, traffic, qualityGatePassed, new BigDecimal("0.95"), new BigDecimal("0.85"),
            new BigDecimal("0.98"), new BigDecimal("0.01"), new BigDecimal("0.01"), 1000L,
            new BigDecimal("0.01"), 1000L, List.of()
        );
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
