package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.CiSarifCredentialResponse;
import com.repoguard.agent.dto.CiSarifUploadResponse;
import com.repoguard.agent.scanner.CiSarifUploadCredentialService;
import com.repoguard.agent.scanner.CiSarifUploadService;
import java.io.InputStream;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CiSarifControllerTest {

    private final CiSarifUploadCredentialService credentialService = org.mockito.Mockito.mock(CiSarifUploadCredentialService.class);
    private final CiSarifUploadService uploadService = org.mockito.Mockito.mock(CiSarifUploadService.class);
    private final CiSarifController controller = new CiSarifController(credentialService, uploadService);

    @Test
    void issuesCredentialWithCurrentAttempt() {
        var issue = new CiSarifUploadCredentialService.TokenIssue(
            "rgci.token.sig", 100L, 9L, 17L, "org", "repo", 42, "abc123"
        );
        when(credentialService.issue(9L, 17L)).thenReturn(issue);

        var response = controller.issueCredential(9L, 17L);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(new CiSarifCredentialResponse(
            "rgci.token.sig", 100L, 9L, 17L, "org", "repo", 42, "abc123"
        ));
    }

    @Test
    void delegatesRawPayloadAndHeadersToUploadService() {
        var expected = new CiSarifUploadResponse(
            9L, 17L, "codeql", "2.1", "run-17", "abc123", "f".repeat(64),
            OffsetDateTime.parse("2026-09-03T10:00:00Z"), "ACTIVE", 1, 0
        );
        when(uploadService.upload(
            eq(9L), eq("credential"), eq("codeql"), eq("2.1"), eq("run-17"), eq("abc123"),
            eq("2026-09-03T10:00:00Z"), eq("application/json"), any()
        )).thenReturn(expected);

        var response = controller.upload(
            9L, "credential", "codeql", "2.1", "run-17", "abc123",
            "2026-09-03T10:00:00Z", "application/json", new byte[] {1, 2}
        );

        assertThat(response.data()).isEqualTo(expected);
        verify(uploadService).upload(
            eq(9L), eq("credential"), eq("codeql"), eq("2.1"), eq("run-17"), eq("abc123"),
            eq("2026-09-03T10:00:00Z"), eq("application/json"), any()
        );
    }

    @Test
    void streamsHttpPayloadWithoutCreatingControllerByteArray() throws Exception {
        var expected = new CiSarifUploadResponse(
            9L, 17L, "codeql", "2.1", "run-stream", "abc123", "f".repeat(64),
            OffsetDateTime.parse("2026-09-03T10:00:00Z"), "ACTIVE", 1, 0
        );
        when(uploadService.upload(
            eq(9L), eq("credential"), eq("codeql"), eq("2.1"), eq("run-stream"), eq("abc123"),
            eq("2026-09-03T10:00:00Z"), eq("application/json"), any(InputStream.class), eq(2L)
        )).thenReturn(expected);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(new byte[] {1, 2});
        var response = controller.upload(
            9L, "credential", "codeql", "2.1", "run-stream", "abc123",
            "2026-09-03T10:00:00Z", "application/json", request
        );

        assertThat(response.data()).isEqualTo(expected);
        verify(uploadService).upload(
            eq(9L), eq("credential"), eq("codeql"), eq("2.1"), eq("run-stream"), eq("abc123"),
            eq("2026-09-03T10:00:00Z"), eq("application/json"), any(InputStream.class), eq(2L)
        );
    }
}
