package com.repoguard.agent.security;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.mapper.UserAccountMapper;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import com.repoguard.agent.tenancy.TenantResolutionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Domain-separated credentials accepted only by the bounded preview endpoint. */
@Service
public class PreflightCredentialService {
    private final AuthProperties properties;
    private final UserAccountMapper users;
    private final TenantProperties tenants;
    private final TenantResolutionService memberships;
    private final Clock clock;

    @Autowired
    public PreflightCredentialService(AuthProperties properties, UserAccountMapper users,
        TenantProperties tenants, TenantResolutionService memberships) {
        this(properties, users, tenants, memberships, Clock.systemUTC());
    }

    PreflightCredentialService(AuthProperties properties, UserAccountMapper users,
        TenantProperties tenants, TenantResolutionService memberships, Clock clock) {
        this.properties = properties; this.users = users; this.tenants = tenants;
        this.memberships = memberships; this.clock = clock;
    }

    public Issue issue(AuthenticatedPrincipal principal, String repository, boolean llmAllowed) {
        long tenant = principal.tenantId() == null ? TenantContext.DEFAULT_TENANT_ID : principal.tenantId();
        long expires = Math.min(principal.expiresAt(), clock.instant().getEpochSecond() + 600);
        Grant grant = new Grant(principal.id(), principal.sessionVersion(), tenant, repository,
            llmAllowed, expires, UUID.randomUUID().toString());
        requireCurrent(grant);
        if (expires <= clock.instant().getEpochSecond()) throw unauthorized();
        String payload = encode("p1:" + properties.getTokenSecretId() + ":" + grant.userId() + ":"
            + grant.sessionVersion() + ":" + tenant + ":" + encode(repository) + ":" + llmAllowed
            + ":" + expires + ":" + grant.nonce());
        return new Issue("rgpf." + payload + "." + sign(payload, properties.getTokenSecret()), expires);
    }

    public Grant verify(String authorization) {
        try {
            if (authorization == null || authorization.length() > 4103
                || !authorization.startsWith("Bearer rgpf.")) throw unauthorized();
            String[] parts = authorization.substring(7).split("\\.", -1);
            if (parts.length != 3 || !parts[1].matches("[A-Za-z0-9_-]+")
                || !parts[2].matches("[A-Za-z0-9_-]+")) throw unauthorized();
            String[] fields = decode(parts[1]).split(":", -1);
            if (fields.length != 9 || !fields[0].equals("p1")) throw unauthorized();
            String secret = fields[1].equals(properties.getTokenSecretId()) ? properties.getTokenSecret()
                : fields[1].equals(properties.getTokenSecretPreviousId()) ? properties.getTokenSecretPrevious() : null;
            if (secret == null || secret.isBlank() || !MessageDigest.isEqual(
                sign(parts[1], secret).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) throw unauthorized();
            if (!fields[6].equals("true") && !fields[6].equals("false")) throw unauthorized();
            Grant grant = new Grant(Long.parseLong(fields[2]), Integer.parseInt(fields[3]),
                Long.parseLong(fields[4]), decode(fields[5]), Boolean.parseBoolean(fields[6]),
                Long.parseLong(fields[7]), fields[8]);
            long now = clock.instant().getEpochSecond();
            if (grant.expiresAt() <= now || grant.expiresAt() > now + 600
                || !grant.nonce().matches("[a-f0-9-]{36}")
                || !validRepository(grant.repository())) throw unauthorized();
            requireCurrent(grant);
            return grant;
        } catch (IllegalArgumentException ex) {
            throw unauthorized();
        }
    }

    private void requireCurrent(Grant grant) {
        if (grant.userId() == null || grant.userId() < 1 || grant.tenantId() < 1
            || grant.sessionVersion() < 0 || !validRepository(grant.repository())) throw unauthorized();
        var user = users.selectById(grant.userId());
        if (user == null || !"ACTIVE".equals(user.getStatus())
            || (user.getSessionVersion() == null ? 0 : user.getSessionVersion()) != grant.sessionVersion())
            throw unauthorized();
        String role = tenants.isEnabled()
            ? memberships.resolve(grant.userId(), grant.tenantId(), null).role() : user.getRole();
        if (!tenants.isEnabled() && grant.tenantId() != TenantContext.DEFAULT_TENANT_ID) throw unauthorized();
        if (!RolePermissionPolicy.allows(role, "VIEWER")
            || (grant.llmAllowed() && !RolePermissionPolicy.allows(role, "ADMIN"))) throw unauthorized();
    }

    public static boolean validRepository(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,39}/[A-Za-z0-9_.-]{1,100}")
            && !value.endsWith("/.") && !value.endsWith("/..");
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(("repoguard:preflight:v1:" + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Preflight signing unavailable", ex);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "Preflight credential is invalid or expired");
    }

    public record Grant(Long userId, int sessionVersion, long tenantId, String repository,
        boolean llmAllowed, long expiresAt, String nonce) { }

    public record Issue(String credential, long expiresAt) {
        @Override public String toString() { return "Issue[credential=REDACTED, expiresAt=" + expiresAt + "]"; }
    }
}
