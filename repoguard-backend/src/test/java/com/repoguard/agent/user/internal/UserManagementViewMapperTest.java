package com.repoguard.agent.user.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.entity.UserOperationAudit;
import com.repoguard.agent.user.UserManagementLifecycle.ManagedUser;
import com.repoguard.agent.user.UserManagementLifecycle.OperationAudit;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UserManagementViewMapperTest {

    private final UserManagementViewMapper mapper = new UserManagementViewMapper();

    @Test
    void mapsUserAccountToManagedUser() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 17, 9, 30);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 6, 18, 10, 15);
        LocalDateTime lockedUntil = LocalDateTime.of(2026, 6, 19, 11, 0);
        LocalDateTime lastLoginAt = LocalDateTime.of(2026, 6, 16, 8, 45);
        UserAccount user = new UserAccount();
        user.setId(42L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setRole("ADMIN");
        user.setStatus("ACTIVE");
        user.setFailedLoginCount(2);
        user.setLockedUntil(lockedUntil);
        user.setLastLoginAt(lastLoginAt);
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(updatedAt);

        ManagedUser result = mapper.toManagedUser(user);

        assertThat(result).isEqualTo(new ManagedUser(
            42L,
            "alice",
            "alice@example.com",
            "ADMIN",
            "ACTIVE",
            2,
            lockedUntil,
            lastLoginAt,
            createdAt,
            updatedAt
        ));
    }

    @Test
    void mapsOperationAuditToAuditView() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 17, 9, 30);
        UserOperationAudit audit = new UserOperationAudit();
        audit.setId(7L);
        audit.setOperatorUserId(1001L);
        audit.setOperatorUsername("admin");
        audit.setTargetUserId(1002L);
        audit.setTargetUsername("viewer");
        audit.setAction("ROLE_UPDATE");
        audit.setBeforeValue("VIEWER");
        audit.setAfterValue("ADMIN");
        audit.setClientIp("10.0.0.1");
        audit.setUserAgent("JUnit");
        audit.setCreatedAt(createdAt);

        OperationAudit result = mapper.toOperationAudit(audit);

        assertThat(result).isEqualTo(new OperationAudit(
            7L,
            1001L,
            "admin",
            1002L,
            "viewer",
            "ROLE_UPDATE",
            "VIEWER",
            "ADMIN",
            "10.0.0.1",
            "JUnit",
            createdAt
        ));
    }
}
