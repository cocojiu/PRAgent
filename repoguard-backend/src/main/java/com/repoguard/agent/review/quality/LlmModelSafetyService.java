package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelRollbackRequest;
import com.repoguard.agent.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Terminal safety action: retain version/evidence and stop queued sends without changing models. */
@Service
public class LlmModelSafetyService {
    private final JdbcTemplate jdbc;
    private final LlmModelReleaseRepository releases;
    private final LlmModelReleaseRuntimeSupport runtime;

    public LlmModelSafetyService(JdbcTemplate jdbc, LlmModelReleaseRepository releases, ObjectMapper json) {
        this.jdbc = jdbc; this.releases = releases; this.runtime = new LlmModelReleaseRuntimeSupport(jdbc, releases, json);
    }

    @Transactional
    public LlmModelReleaseDto revoke(long releaseId, LlmModelRollbackRequest request, String operator) {
        if (request == null || request.reason() == null || request.reason().isBlank() || request.reason().length() > 512) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "安全撤销必须填写原因");
        }
        long tenant = TenantContext.currentTenantIdOrDefault();
        releases.lockTenant(tenant);
        LlmModelReleaseDto before = releases.findById(tenant, releaseId);
        if (before == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布不存在");
        if (before.safetyRevoked()) return before;
        jdbc.update("""
            update llm_model_release set safety_revoked_at=utc_timestamp(6),safety_revoke_reason=?,
                traffic_percent=0,updated_at=utc_timestamp(6)
            where tenant_id=? and id=? and safety_revoked_at is null
            """, request.reason().trim(), tenant, releaseId);
        LlmModelReleaseDto after = releases.findById(tenant, releaseId);
        runtime.audit(tenant, "SAFETY_REVOKE", before, after, operator, request.reason().trim());
        return after;
    }
}
