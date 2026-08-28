package com.repoguard.agent.tenancy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.mapper.TenantMembershipMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TenantResolutionService {

    private final TenantMembershipMapper membershipMapper;

    public TenantResolutionService(TenantMembershipMapper membershipMapper) {
        this.membershipMapper = Objects.requireNonNull(membershipMapper, "membershipMapper");
    }

    public TenantMembershipView resolve(Long userId, Long identityTenantId, String requestedTenantKey) {
        if (userId == null || userId < 0) {
            throw forbidden();
        }
        if (userId == 0) {
            if (identityTenantId != null && identityTenantId > 0) {
                return new TenantMembershipView(identityTenantId, requestedTenantKey, "ADMIN", true);
            }
            return new TenantMembershipView(TenantContext.DEFAULT_TENANT_ID, "default", "ADMIN", true);
        }
        List<TenantMembershipView> memberships = membershipMapper.selectActiveMemberships(userId);
        if (memberships == null || memberships.isEmpty()) {
            throw forbidden();
        }
        if (identityTenantId != null) {
            return memberships.stream()
                .filter(membership -> identityTenantId.equals(membership.tenantId()))
                .findFirst()
                .orElseThrow(TenantResolutionService::forbidden);
        }
        if (StringUtils.hasText(requestedTenantKey)) {
            String normalized = requestedTenantKey.trim();
            return memberships.stream()
                .filter(membership -> membership.tenantKey().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(TenantResolutionService::forbidden);
        }
        return memberships.stream()
            .filter(TenantMembershipView::defaultTenant)
            .findFirst()
            .orElseGet(() -> memberships.size() == 1 ? memberships.getFirst() : requireSelection());
    }

    private static TenantMembershipView requireSelection() {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant selection is required");
    }

    private static BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN, "Tenant access is not allowed");
    }
}
