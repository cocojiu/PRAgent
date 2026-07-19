package com.repoguard.agent.web;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Web adapter for reading the neutral authenticated principal from a request.
 */
public final class RequestAuthentication {

    private RequestAuthentication() {
    }

    public static Optional<AuthenticatedPrincipal> find(HttpServletRequest request) {
        Object principal = request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL);
        return principal instanceof AuthenticatedPrincipal authenticatedPrincipal
            ? Optional.of(authenticatedPrincipal)
            : Optional.empty();
    }

    public static AuthenticatedPrincipal require(HttpServletRequest request) {
        return find(request).orElseThrow(() -> new BusinessException(
            ErrorCode.UNAUTHORIZED,
            "Authentication token is required"
        ));
    }
}
