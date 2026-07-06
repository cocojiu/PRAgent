package com.repoguard.agent.user;

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
        assertThat(policy.activeStatus()).isEqualTo("ACTIVE");
        assertThat(policy.isAdmin(admin)).isTrue();
        assertThat(policy.isAdmin(null)).isFalse();
        assertThat(policy.isViewerRole("VIEWER")).isTrue();
        assertThat(policy.isActiveStatus("ACTIVE")).isTrue();
        assertThat(policy.isDisabledStatus("DISABLED")).isTrue();
    }
}
