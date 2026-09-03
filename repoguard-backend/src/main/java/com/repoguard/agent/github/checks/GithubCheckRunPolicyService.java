package com.repoguard.agent.github.checks;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.GithubCheckRunPolicy;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunPolicyMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class GithubCheckRunPolicyService implements GithubCheckRunPolicyProvider {

    private final GithubCheckRunPolicyMapper mapper;

    public GithubCheckRunPolicyService(GithubCheckRunPolicyMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public boolean isEnabled(ReviewTask task) {
        if (task == null || !StringUtils.hasText(task.getOrganization()) || !StringUtils.hasText(task.getRepository())) {
            return false;
        }
        GithubCheckRunPolicy policy = mapper.selectByRepository(task.getOrganization(), task.getRepository());
        // An explicit confirmation row is required; absence must remain fail-closed.
        return policy != null && Boolean.TRUE.equals(policy.getEnabled());
    }

    public GithubCheckRunPolicy find(String organization, String repository) {
        return mapper.selectByRepository(normalize(organization, "organization"), normalize(repository, "repository"));
    }

    @Transactional
    public GithubCheckRunPolicy setEnabled(
        String organization,
        String repository,
        boolean enabled,
        long expectedVersion,
        String operator
    ) {
        String normalizedOrganization = normalize(organization, "organization");
        String normalizedRepository = normalize(repository, "repository");
        if (expectedVersion < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Check Run policy version must not be negative");
        }
        String normalizedOperator = StringUtils.hasText(operator) ? operator.trim() : "unknown";
        GithubCheckRunPolicy current = mapper.selectByRepository(normalizedOrganization, normalizedRepository);
        LocalDateTime now = LocalDateTime.now();
        if (current == null) {
            if (expectedVersion != 0) {
                throw conflict();
            }
            GithubCheckRunPolicy created = new GithubCheckRunPolicy();
            created.setTenantId(TenantContext.currentTenantIdOrDefault());
            created.setOrganization(normalizedOrganization);
            created.setRepository(normalizedRepository);
            created.setEnabled(enabled);
            created.setPolicyVersion(1L);
            created.setUpdatedBy(normalizedOperator);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            mapper.insert(created);
            return created;
        }
        if (!Objects.equals(current.getPolicyVersion(), expectedVersion)) {
            throw conflict();
        }
        if (mapper.updateEnabled(
            current.getId(), enabled, expectedVersion, normalizedOperator, now
        ) != 1) {
            throw conflict();
        }
        current.setEnabled(enabled);
        current.setPolicyVersion(expectedVersion + 1L);
        current.setUpdatedBy(normalizedOperator);
        current.setUpdatedAt(now);
        return current;
    }

    private String normalize(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 255 || normalized.contains("/") || normalized.contains("\\")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " has an invalid format");
        }
        return normalized;
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "Check Run policy changed; reload before confirming");
    }
}
