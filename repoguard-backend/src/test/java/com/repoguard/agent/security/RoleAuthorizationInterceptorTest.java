package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class RoleAuthorizationInterceptorTest {

    private final RoleAuthorizationInterceptor interceptor = new RoleAuthorizationInterceptor(new ObjectMapper());

    @Test
    void allowsAdminWhenAdminRoleRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/config/system-settings");
        request.setAttribute(
            AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
            new AuthTokenService.AuthenticatedUser(1001L, "admin", "ADMIN", 9999999999L)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("adminOnly"));

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsViewerWhenAdminRoleRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/config/system-settings");
        request.setAttribute(
            AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
            new AuthTokenService.AuthenticatedUser(1002L, "viewer", "VIEWER", 9999999999L)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("adminOnly"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void allowsViewerWhenEndpointExplicitlyAcceptsViewerOrAdmin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/observability/frontend/performance");
        request.setAttribute(
            AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
            new AuthTokenService.AuthenticatedUser(1002L, "viewer", "VIEWER", 9999999999L)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("adminOrViewer"));

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingAuthenticatedUserWhenRoleRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/config/system-settings");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("adminOnly"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void allowsEndpointWithoutRoleRequirement() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("readOnly"));

        assertThat(allowed).isTrue();
    }

    private HandlerMethod handlerMethod(String methodName) throws NoSuchMethodException {
        Method method = DemoController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(new DemoController(), method);
    }

    private static class DemoController {
        @RequireRole("ADMIN")
        void adminOnly() {
        }

        @RequireRole({"ADMIN", "VIEWER"})
        void adminOrViewer() {
        }

        void readOnly() {
        }
    }
}
