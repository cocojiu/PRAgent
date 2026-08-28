package com.repoguard.agent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.identity.internal.DefaultEnterpriseOidcAuthenticator;
import com.repoguard.agent.mapper.EnterpriseIdentityMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

class EnterpriseOidcAuthenticatorTest {

    private final EnterpriseOidcProperties properties = properties();
    private final EnterpriseIdentityMapper mapper = mock(EnterpriseIdentityMapper.class);
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final DefaultEnterpriseOidcAuthenticator authenticator = authenticator();

    @Test
    void authenticatesBoundActiveIdentityWithAudienceAndMfa() {
        Jwt jwt = jwt(List.of("repoguard-api"), List.of("pwd", "mfa"));
        when(decoder.decode("token")).thenReturn(jwt);
        when(mapper.selectActiveIdentity("https://identity.example.com", "subject-1"))
            .thenReturn(new EnterpriseIdentityView(8L, 9L, "alice", "VIEWER", "ACTIVE", 4));

        Optional<AuthenticatedPrincipal> result = authenticator.authenticate("token");

        assertThat(result).contains(
            new AuthenticatedPrincipal(9L, "alice", "VIEWER", jwt.getExpiresAt().getEpochSecond(), 4, 8L)
        );
    }

    @Test
    void rejectsWrongAudienceBeforeIdentityLookup() {
        when(decoder.decode("token")).thenReturn(jwt(List.of("another-api"), List.of("mfa")));

        assertThat(authenticator.authenticate("token")).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsTokenWithoutRequiredMfa() {
        when(decoder.decode("token")).thenReturn(jwt(List.of("repoguard-api"), List.of("pwd")));

        assertThat(authenticator.authenticate("token")).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsInactiveIdentity() {
        when(decoder.decode("token")).thenReturn(jwt(List.of("repoguard-api"), "mfa"));
        when(mapper.selectActiveIdentity("https://identity.example.com", "subject-1"))
            .thenReturn(new EnterpriseIdentityView(8L, 9L, "alice", "ADMIN", "DISABLED", 1));

        assertThat(authenticator.authenticate("token")).isEmpty();
    }

    @Test
    void disabledOidcDoesNotDecodeToken() {
        properties.setEnabled(false);

        assertThat(authenticator.authenticate("token")).isEmpty();
        verifyNoInteractions(decoder, mapper);
    }

    private DefaultEnterpriseOidcAuthenticator authenticator() {
        DefaultEnterpriseOidcAuthenticator value = new DefaultEnterpriseOidcAuthenticator(properties, mapper);
        ReflectionTestUtils.setField(value, "decoder", decoder);
        return value;
    }

    private EnterpriseOidcProperties properties() {
        EnterpriseOidcProperties value = new EnterpriseOidcProperties();
        value.setEnabled(true);
        value.setIssuerUri("https://identity.example.com");
        value.setAudience("repoguard-api");
        value.setRequiredAmr("mfa");
        return value;
    }

    private Jwt jwt(List<String> audience, Object amr) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("https://identity.example.com")
            .subject("subject-1")
            .audience(audience)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("amr", amr)
            .build();
    }
}
