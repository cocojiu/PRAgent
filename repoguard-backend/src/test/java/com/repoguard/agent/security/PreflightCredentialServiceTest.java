package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.tenancy.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreflightCredentialServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private final AuthProperties properties = new AuthProperties();
    private final UserAccountMapper users = mock(UserAccountMapper.class);
    private final TenantProperties tenants = new TenantProperties();
    private final TenantResolutionService memberships = mock(TenantResolutionService.class);
    private UserAccount user;
    private PreflightCredentialService service;

    @BeforeEach void setup() {
        properties.setTokenSecret(UUID.randomUUID().toString());
        user = new UserAccount(); user.setId(7L); user.setStatus("ACTIVE");
        user.setRole("ADMIN"); user.setSessionVersion(3);
        when(users.selectById(7L)).thenReturn(user);
        service = at(NOW);
    }
    private PreflightCredentialService at(Instant time) {
        return new PreflightCredentialService(properties, users, tenants, memberships, Clock.fixed(time, ZoneOffset.UTC));
    }
    private AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal(7L, "fixture", "ADMIN", NOW.getEpochSecond() + 900, 3, 1L);
    }
    private String header(boolean llm) { return "Bearer " + service.issue(principal(), "fixture/repo", llm).credential(); }

    @Test void shortCredentialIsBoundAndCannotAuthenticateOrdinaryApi() {
        var issued = service.issue(principal(), "fixture/repo", true);
        var grant = service.verify("Bearer " + issued.credential());
        assertThat(grant.repository()).isEqualTo("fixture/repo");
        assertThat(grant.userId()).isEqualTo(7L); assertThat(grant.tenantId()).isEqualTo(1L);
        assertThat(grant.expiresAt()).isEqualTo(NOW.getEpochSecond() + 600);
        assertThat(grant.llmAllowed()).isTrue();
        assertThat(new AuthTokenService(properties).verify(issued.credential())).isEmpty();
        assertThat(issued.toString()).doesNotContain(issued.credential());
    }
    @Test void refusesOrdinaryBearerAdminAndCiCredentials() {
        var access = new AuthTokenService(properties).issueAccessToken(user).token();
        for (String header : new String[]{null, "Bearer " + access, "Bearer " + UUID.randomUUID(), "Bearer rgpf.a.b",
            "Bearer rgpf." + "a".repeat(5000) + ".b"}) {
            assertThatThrownBy(() -> service.verify(header)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void tamperAndExpiryAreRejected() {
        String header = header(false);
        assertThatThrownBy(() -> service.verify(header + "x")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> at(NOW.plusSeconds(600)).verify(header)).isInstanceOf(BusinessException.class);
    }
    @Test void currentSessionDisabledAccountAndRoleAreRechecked() {
        String header = header(true);
        user.setSessionVersion(4);
        assertThatThrownBy(() -> service.verify(header)).isInstanceOf(BusinessException.class);
        user.setSessionVersion(3); user.setStatus("DISABLED");
        assertThatThrownBy(() -> service.verify(header)).isInstanceOf(BusinessException.class);
        user.setStatus("ACTIVE"); user.setRole("VIEWER");
        assertThatThrownBy(() -> service.verify(header)).isInstanceOf(BusinessException.class);
    }
    @Test void viewerMayMintRulesButCannotMintModelOrAdminKeyIdentity() {
        user.setRole("VIEWER");
        assertThat(service.verify(header(false)).llmAllowed()).isFalse();
        assertThatThrownBy(() -> header(true)).isInstanceOf(BusinessException.class);
        var keyPrincipal = new AuthenticatedPrincipal(0L, "key", "ADMIN", Long.MAX_VALUE, 0);
        assertThatThrownBy(() -> service.issue(keyPrincipal, "fixture/repo", false)).isInstanceOf(BusinessException.class);
    }
    @Test void enterpriseMembershipAndTenantAreRevalidated() {
        tenants.setEnabled(true);
        when(memberships.resolve(7L, 1L, null)).thenReturn(new TenantMembershipView(1L, "fixture", "ADMIN", true));
        String header = header(true);
        assertThat(service.verify(header).tenantId()).isEqualTo(1L);
        when(memberships.resolve(7L, 1L, null)).thenReturn(new TenantMembershipView(1L, "fixture", "VIEWER", true));
        assertThatThrownBy(() -> service.verify(header)).isInstanceOf(BusinessException.class);
    }
    @Test void keyRotationRetainsOnlyConfiguredPreviousKey() {
        String header = header(false);
        properties.setTokenSecretPreviousId(properties.getTokenSecretId());
        properties.setTokenSecretPrevious(properties.getTokenSecret());
        properties.setTokenSecretId("next"); properties.setTokenSecret(UUID.randomUUID().toString());
        assertThat(service.verify(header).userId()).isEqualTo(7L);
        properties.setTokenSecretPrevious(null);
        assertThatThrownBy(() -> service.verify(header)).isInstanceOf(BusinessException.class);
    }
}
