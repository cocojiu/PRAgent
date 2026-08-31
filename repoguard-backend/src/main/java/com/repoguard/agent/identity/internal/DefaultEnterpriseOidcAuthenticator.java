package com.repoguard.agent.identity.internal;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.EnterpriseOidcAuthenticator;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.identity.EnterpriseIdentityView;
import com.repoguard.agent.identity.EnterpriseOidcProperties;
import com.repoguard.agent.mapper.EnterpriseIdentityMapper;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@EnterpriseEditionEnabled
public final class DefaultEnterpriseOidcAuthenticator implements EnterpriseOidcAuthenticator {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final EnterpriseOidcProperties properties;
    private final EnterpriseIdentityMapper identityMapper;
    private volatile JwtDecoder decoder;

    public DefaultEnterpriseOidcAuthenticator(
        EnterpriseOidcProperties properties,
        EnterpriseIdentityMapper identityMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.identityMapper = Objects.requireNonNull(identityMapper, "identityMapper");
    }

    @Override
    public Optional<AuthenticatedPrincipal> authenticate(String token) {
        if (!properties.isEnabled() || !StringUtils.hasText(token)) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder().decode(token);
            if (!validAudience(jwt) || !validAmr(jwt)) {
                return Optional.empty();
            }
            URL issuer = jwt.getIssuer();
            String subject = jwt.getSubject();
            if (issuer == null || !StringUtils.hasText(subject)) {
                return Optional.empty();
            }
            EnterpriseIdentityView identity = identityMapper.selectActiveIdentity(issuer.toString(), subject);
            if (identity == null || !STATUS_ACTIVE.equals(identity.status())) {
                return Optional.empty();
            }
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedPrincipal(
                identity.userId(),
                identity.username(),
                identity.role(),
                expiresAt.getEpochSecond(),
                identity.sessionVersion() == null ? 0 : identity.sessionVersion(),
                identity.tenantId()
            ));
        } catch (JwtException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    private boolean validAudience(Jwt jwt) {
        return StringUtils.hasText(properties.getAudience())
            && jwt.getAudience().contains(properties.getAudience().trim());
    }

    private boolean validAmr(Jwt jwt) {
        if (!StringUtils.hasText(properties.getRequiredAmr())) {
            return true;
        }
        String required = properties.getRequiredAmr().trim();
        Object claim = jwt.getClaims().get("amr");
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).anyMatch(required::equalsIgnoreCase);
        }
        return claim != null && List.of(String.valueOf(claim).split("[ ,]")).stream()
            .anyMatch(required::equalsIgnoreCase);
    }

    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (decoder == null) {
                decoder = JwtDecoders.fromIssuerLocation(requiredHttpsIssuer());
            }
            return decoder;
        }
    }

    private String requiredHttpsIssuer() {
        if (!StringUtils.hasText(properties.getIssuerUri())) {
            throw new IllegalStateException("Enterprise OIDC issuer URI is required");
        }
        String issuer = properties.getIssuerUri().trim();
        URI uri = URI.create(issuer);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("Enterprise OIDC issuer URI must use HTTPS");
        }
        return issuer;
    }
}
