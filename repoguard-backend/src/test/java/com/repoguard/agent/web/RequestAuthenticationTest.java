package com.repoguard.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestAuthenticationTest {

    @Test
    void findsNeutralAuthenticatedPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            1001L,
            "admin",
            "ADMIN",
            9999999999L,
            7
        );
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal);

        assertThat(RequestAuthentication.find(request)).contains(principal);
    }

    @Test
    void ignoresUnexpectedAttributeType() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, "legacy-principal");

        assertThat(RequestAuthentication.find(request)).isEmpty();
    }

    @Test
    void requireRejectsMissingPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> RequestAuthentication.require(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Authentication token is required");
    }
}
