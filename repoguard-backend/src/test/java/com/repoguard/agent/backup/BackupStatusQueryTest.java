package com.repoguard.agent.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.controller.BackupStatusController;
import com.repoguard.agent.security.RoleAuthorizationInterceptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BackupStatusQueryTest {
    @TempDir Path directory;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new JacksonConfig().objectMapper();

    private Map<String, Object> success() {
        long now = Instant.now().getEpochSecond();
        return new HashMap<>(Map.of("scope", "mysql-logical-snapshot", "state", "success",
            "startedAtUnix", now - 90, "finishedAtUnix", now - 30, "retained", 3,
            "archive", Map.of("bytes", 1024), "storageBudgetExceeded", false));
    }

    private BackupStatusQuery query(Map<String, Object> record) throws Exception {
        Path file = directory.resolve("status.json");
        json.writeValue(file.toFile(), record);
        return new BackupStatusQuery(json, file.toString(), 30);
    }

    @Test void disabledAndMissingAreExplicit() {
        assertThat(new BackupStatusQuery(json, "", 30).read().status()).isEqualTo("DISABLED");
        assertThat(new BackupStatusQuery(json, directory.resolve("missing").toString(), 30).read().status()).isEqualTo("MISSING");
        assertThat(new BackupStatusQuery(json, "relative.json", 30).read().status()).isEqualTo("UNREADABLE_OR_INVALID");
        assertThatThrownBy(() -> new BackupStatusQuery(json, "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackupStatusQuery(json, "", 169)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void projectsSuccessWithoutPathsOrRecoveryClaims() throws Exception {
        var input = success(); input.put("extra", "private-fixture-value");
        input.put("archive", Map.of("bytes", 1024, "name", "private-fixture-name", "sha256", "private-fixture-hash"));
        var value = query(input).read();
        assertThat(value.status()).isEqualTo("SUCCESS_RECORDED");
        assertThat(value.retained()).isEqualTo(3); assertThat(value.archiveBytes()).isEqualTo(1024L);
        assertThat(value.restoreVerified()).isFalse(); assertThat(value.archiveIntegrityChecked()).isFalse();
        assertThat(value.processLivenessChecked()).isFalse(); assertThat(value.timerEnabledChecked()).isFalse();
        assertThat(json.writeValueAsString(value)).doesNotContain("private-fixture", directory.toString());
    }

    @Test void staleAndStorageBudgetRemainWarnings() throws Exception {
        var input = success(); input.put("startedAtUnix", Instant.now().minusSeconds(120000).getEpochSecond());
        input.put("finishedAtUnix", Instant.now().minusSeconds(110000).getEpochSecond());
        assertThat(query(input).read().status()).isEqualTo("STALE");
        input.put("storageBudgetExceeded", true);
        assertThat(query(input).read().status()).isEqualTo("STORAGE_LIMIT");
    }

    @Test void runningRecordDoesNotClaimTheProcessIsAlive() throws Exception {
        var input = success(); input.put("state", "running"); input.remove("finishedAtUnix");
        assertThat(query(input).read().status()).isEqualTo("RUNNING_RECORDED");
        input.put("startedAtUnix", Instant.now().minusSeconds(1000).getEpochSecond());
        assertThat(query(input).read().status()).isEqualTo("RUN_OVERRUN");
    }

    @Test void onlyAllowsKnownFailureReasonCodes() throws Exception {
        var input = success(); input.put("state", "failed"); input.put("reason", "service_interrupted");
        assertThat(query(input).read().reasonCode()).isEqualTo("service_interrupted");
        input.put("reason", "private-fixture-value");
        assertThat(query(input).read().reasonCode()).isEqualTo("unspecified_failure");
    }

    @Test void rejectsInvalidNumbersAndScope() throws Exception {
        for (var invalid : Map.of("startedAtUnix", true, "finishedAtUnix", Long.MAX_VALUE,
            "retained", 0, "storageBudgetExceeded", "false", "scope", "other", "state", "unknown").entrySet()) {
            var input = success(); input.put(invalid.getKey(), invalid.getValue());
            assertThat(query(input).read().status()).as(invalid.getKey()).isEqualTo("UNREADABLE_OR_INVALID");
        }
        var input = success(); input.put("archive", Map.of("bytes", -1));
        assertThat(query(input).read().status()).isEqualTo("UNREADABLE_OR_INVALID");
    }

    @Test void boundsMalformedAndOversizedFileReads() throws Exception {
        Path file = directory.resolve("status.json");
        var query = new BackupStatusQuery(json, file.toString(), 30);
        for (String raw : new String[]{"{", "null", " ".repeat(65537)}) {
            Files.writeString(file, raw);
            assertThat(query.read().status()).isEqualTo("UNREADABLE_OR_INVALID");
        }
        assertThat(new BackupStatusQuery(json, directory.toString(), 30).read().status()).isEqualTo("UNREADABLE_OR_INVALID");
    }

    @Test void metadataEndpointRequiresSystemAdministratorAndDisablesCaching() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new BackupStatusController(query(success())))
            .addInterceptors(new RoleAuthorizationInterceptor(json)).build();
        mvc.perform(get("/api/v1/system/backup-status")).andExpect(status().isUnauthorized());
        for (String role : new String[]{"VIEWER", "TENANT_ADMIN", "RULE_ADMIN", "REVIEWER"}) {
            mvc.perform(get("/api/v1/system/backup-status").requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
                new AuthenticatedPrincipal(7L, "fixture", role, Long.MAX_VALUE))).andExpect(status().isForbidden());
        }
        for (String role : new String[]{"ADMIN", "PLATFORM_ADMIN"}) {
            mvc.perform(get("/api/v1/system/backup-status").requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
                new AuthenticatedPrincipal(7L, "fixture", role, Long.MAX_VALUE)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS_RECORDED"));
        }
    }
}
