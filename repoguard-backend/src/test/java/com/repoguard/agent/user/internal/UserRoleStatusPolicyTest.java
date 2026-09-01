package com.repoguard.agent.user.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.UserAccount;
import org.junit.jupiter.api.Test;

class UserRoleStatusPolicyTest {

    private final UserRoleStatusPolicy policy = new UserRoleStatusPolicy();

    @Test
    void normalizesSupportedRolesAndStatuses() {
        assertThat(policy.normalizeRole("ADMIN")).isEqualTo("ADMIN");
        assertThat(policy.normalizeRole("VIEWER")).isEqualTo("VIEWER");
        assertThat(policy.normalizeRole(" platform_admin ")).isEqualTo("PLATFORM_ADMIN");
        assertThat(policy.normalizeRole("TENANT_ADMIN")).isEqualTo("TENANT_ADMIN");
        assertThat(policy.normalizeRole("RULE_ADMIN")).isEqualTo("RULE_ADMIN");
        assertThat(policy.normalizeRole("REVIEWER")).isEqualTo("REVIEWER");
        assertThat(policy.normalizeRole("READ_ONLY")).isEqualTo("READ_ONLY");
        assertThat(policy.normalizeStatus("ACTIVE")).isEqualTo("ACTIVE");
        assertThat(policy.normalizeStatus("DISABLED")).isEqualTo("DISABLED");
    }

    @Test
    void rejectsUnsupportedRolesAndStatuses() {
        assertThatThrownBy(() -> policy.normalizeRole("OWNER"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Unsupported user role");
        assertThatThrownBy(() -> policy.normalizeStatus("LOCKED"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Unsupported user status");
    }

    @Test
    void exposesRoleAndStatusChecks() {
        UserAccount admin = new UserAccount();
        admin.setRole("ADMIN");

        assertThat(policy.adminRole()).isEqualTo("ADMIN");
        assertThat(policy.viewerRole()).isEqualTo("VIEWER");
        assertThat(policy.platformAdminRole()).isEqualTo("PLATFORM_ADMIN");
        assertThat(policy.tenantAdminRole()).isEqualTo("TENANT_ADMIN");
        assertThat(policy.ruleAdminRole()).isEqualTo("RULE_ADMIN");
        assertThat(policy.reviewerRole()).isEqualTo("REVIEWER");
        assertThat(policy.readOnlyRole()).isEqualTo("READ_ONLY");
        assertThat(policy.activeStatus()).isEqualTo("ACTIVE");
        assertThat(policy.isAdmin(admin)).isTrue();
        admin.setRole("PLATFORM_ADMIN");
        assertThat(policy.isAdmin(admin)).isTrue();
        assertThat(policy.isAdmin(null)).isFalse();
        assertThat(policy.isViewerRole("VIEWER")).isTrue();
        assertThat(policy.isActiveStatus("ACTIVE")).isTrue();
        assertThat(policy.isDisabledStatus("DISABLED")).isTrue();
    }
}
