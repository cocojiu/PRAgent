package com.repoguard.agent.authentication;

import java.util.Optional;

/**
 * Neutral authentication port for verifying enterprise OIDC bearer tokens.
 */
public interface EnterpriseOidcAuthenticator {

    Optional<AuthenticatedPrincipal> authenticate(String token);
}
