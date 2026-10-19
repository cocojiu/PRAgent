package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Real immutable application, queue, database and browser; only upstream suppliers are stubs. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_ISOLATED_REVIEW_ACCEPTANCE", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IsolatedReviewAcceptanceLinuxIntegrationTest {
    @TempDir static Path temporary;
    private static IsolatedReviewRuntime runtime;
    private static SupplierStub supplier;
    private static long taskId;

    @BeforeAll static void prepare() throws Exception {
        runtime = new IsolatedReviewRuntime(temporary);
        runtime.prepare();
        supplier = new SupplierStub(temporary, runtime.trustPassword(), runtime.gateway);
        runtime.startApplications();
        supplier.expectedGithubToken = runtime.githubToken; supplier.expectedLlmKey = runtime.llmApiKey;
        runtime.enableSqlObservation();
    }

    @Test @Order(1) @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realLongReviewSurvivesNativeBackgroundAndNetworkRecovery() throws Exception {
        taskId = runtime.request("POST", "/api/v1/reviews/manual", Map.of("organization", "fixture", "repository", "sample",
            "prNumber", 1, "title", "Isolated long review", "commit", SupplierStub.COMMIT, "branch", "acceptance", "source", "MANUAL"), runtime.token).path("taskId").asLong();
        assertThat(taskId).isPositive();
        assertThat(supplier.llmEntered.await(45, TimeUnit.SECONDS)).as("Real worker must reach the isolated HTTPS model boundary").isTrue();
        Instant heldAt = Instant.now();
        long heldMillis = 0;
        assertThat(runtime.sql("SELECT COUNT(*) FROM review_execution_attempt WHERE task_id=" + taskId)).isEqualTo("1");
        String attempt = runtime.sql("SELECT current_attempt_id FROM review_task WHERE id=" + taskId);
        runtime.proof.put("taskId", taskId); runtime.proof.put("attemptId", attempt); runtime.save();
        runtime.captureReadCounters("stream-before", taskId);
        runtime.request("POST", "/api/v1/auth/register", Map.of("username", "subscriber", "email", "subscriber@example.test", "password", runtime.password, "confirmPassword", runtime.password), null);
        String subscriberToken = runtime.request("POST", "/api/v1/auth/login", Map.of("account", "subscriber", "password", runtime.password), null).path("accessToken").asText();
        runtime.secrets.add(subscriberToken);
        var transport = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        try (var firstSubscriber = new IsolatedSseSubscriber(runtime, taskId, subscriberToken, "0");
             var futureCursorSubscriber = new IsolatedSseSubscriber(runtime, taskId, subscriberToken, Long.toString(Long.MAX_VALUE));
             Playwright playwright = Playwright.create(); NativeHeadedBrowser nativeBrowser = new NativeHeadedBrowser(playwright, temporary, runtime.evidence, runtime.baseUrl)) {
            firstSubscriber.awaitProgress(); futureCursorSubscriber.awaitProgress();
            var context = nativeBrowser.browser.contexts().getFirst();
            // Listen to genuine events only: no property replacement and no synthetic dispatch.
            context.addInitScript("window.acceptanceVisibility = [{state:document.visibilityState,at:Date.now()}]; document.addEventListener('visibilitychange',()=>window.acceptanceVisibility.push({state:document.visibilityState,at:Date.now()}));");
            Page page = context.newPage(); page.bringToFront();
            page.onRequest(request -> {
                if (request.url().contains("/events") || request.url().contains("/status")) transport.add(Map.of("event", "request", "path", URI.create(request.url()).getPath(), "at", Instant.now().toString()));
            });
            page.onResponse(response -> {
                if (response.url().contains("/events") || response.url().contains("/status")) transport.add(Map.of("event", "response", "path", URI.create(response.url()).getPath(), "status", response.status(), "traceId", java.util.Objects.toString(response.headerValue("X-Trace-Id"), ""), "at", Instant.now().toString()));
            });
            page.navigate(runtime.baseUrl + "/login");
            page.getByPlaceholder("请输入用户名或邮箱").fill("acceptance");
            page.getByPlaceholder("请输入密码", new Page.GetByPlaceholderOptions().setExact(true)).fill(runtime.password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录").setExact(true)).click();
            page.waitForURL("**/repoguard/**");
            page.navigate(runtime.baseUrl + "/repoguard/tasks/" + taskId);
            page.waitForCondition(() -> transport.stream().anyMatch(row -> row.get("event").equals("response") && row.get("path").toString().endsWith("/events") && row.get("status").equals(200)));
            // The plain browser was attached with noDefaults; tab activation now changes native visibility.
            Page background = context.newPage(); background.navigate("about:blank");
            background.bringToFront();
            page.waitForCondition(() -> "hidden".equals(page.evaluate("document.visibilityState")));
            page.waitForTimeout(12_000); // At least two real five-second sampling periods.
            page.bringToFront(); page.waitForCondition(() -> "visible".equals(page.evaluate("document.visibilityState")));
            context.setOffline(true); page.waitForTimeout(6_000);
            assertThat(page.evaluate("navigator.onLine")).isEqualTo(false);
            context.setOffline(false); page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate("navigator.onLine")));
            page.waitForTimeout(6_000);
            String replacement = "Ci!9-" + runtime.secret();
            runtime.request("POST", "/api/v1/auth/password/change", Map.of("currentPassword", runtime.password, "newPassword", replacement, "confirmPassword", replacement), subscriberToken);
            firstSubscriber.awaitRevoked(); futureCursorSubscriber.awaitRevoked();
            try (var unaffected = new IsolatedSseSubscriber(runtime, taskId, runtime.token, "0")) { unaffected.awaitProgress(); }
            runtime.proof.put("subscriberAuthorization", Map.of("revoked", List.of(firstSubscriber.summary(), futureCursorSubscriber.summary()),
                "otherUserStillAuthorized", true, "futureCursorResynchronized", true)); runtime.save();
            var visibility = page.evaluate("window.acceptanceVisibility");
            Files.writeString(runtime.evidence.resolve("visibility.json"), IsolatedReviewRuntime.JSON.writeValueAsString(visibility));
            assertThat(IsolatedReviewRuntime.JSON.writeValueAsString(visibility)).contains("hidden", "visible");
            long remaining = 65_000 - Duration.between(heldAt, Instant.now()).toMillis();
            if (remaining > 0) page.waitForTimeout(remaining);
            assertThat(Duration.between(heldAt, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(60));
            heldMillis = Duration.between(heldAt, Instant.now()).toMillis();
            supplier.releaseLlm.countDown();
            IsolatedReviewRuntime.await(() -> {
                try { return runtime.sql("SELECT status FROM review_task WHERE id=" + taskId).matches("COMPLETED|PENDING_HUMAN_REVIEW"); }
                catch (Exception e) { return false; }
            }, 45);
            page.waitForTimeout(6_000);
            page.screenshot(new Page.ScreenshotOptions().setPath(runtime.evidence.resolve("completed-review.png")));
            assertThat(page.locator("body").innerText()).contains("Isolated long review");
            page.navigate(runtime.baseUrl + "/repoguard/tasks"); page.waitForTimeout(6_000);
            // NativeHeadedBrowser owns and closes the entire isolated default context/process.
        } finally {
            supplier.releaseLlm.countDown();
            Files.writeString(runtime.evidence.resolve("browser-transport.json"), IsolatedReviewRuntime.JSON.writeValueAsString(transport));
            Files.writeString(runtime.evidence.resolve("supplier-requests.json"), IsolatedReviewRuntime.JSON.writeValueAsString(supplier.requests));
        }
        assertThat(supplier.llmCalls.get()).isEqualTo(1);
        assertThat(runtime.sql("SELECT COUNT(*) FROM review_execution_attempt WHERE task_id=" + taskId)).isEqualTo("1");
        assertThat(runtime.sql("SELECT llm_status FROM review_task WHERE id=" + taskId)).isEqualTo("COMPLETED");
        assertThat(runtime.sql("SELECT COUNT(*) FROM changed_file WHERE task_id=" + taskId)).isEqualTo("1");
        assertThat(Integer.parseInt(runtime.sql("SELECT COUNT(*) FROM review_finding WHERE task_id=" + taskId + " AND source='LLM'"))).isPositive();
        var duplicate = runtime.request("POST", "/api/v1/reviews/manual", Map.of("organization", "fixture", "repository", "sample",
            "prNumber", 1, "title", "Isolated long review", "commit", SupplierStub.COMMIT, "branch", "acceptance", "source", "MANUAL"), runtime.token);
        runtime.publishTerminalDuplicate(taskId);
        assertThat(runtime.sql("SELECT COUNT(*) FROM review_execution_attempt WHERE task_id=" + taskId)).isEqualTo("1");
        assertThat(supplier.llmCalls.get()).isEqualTo(1);
        runtime.proof.put("rabbitDuplicate", Map.of("publisherConfirmed", true, "queueDrained", true, "executionAttempts", 1, "llmCalls", 1));
        assertThat(duplicate.path("existing").asBoolean()).isTrue(); assertThat(duplicate.path("taskId").asLong()).isEqualTo(taskId);
        runtime.proof.put("longReview", Map.of("passed", true, "heldMilliseconds", heldMillis, "llmCalls", supplier.llmCalls.get(), "duplicateTaskReused", true));
        runtime.captureReadCounters("stream-after", taskId);
        runtime.proof.put("browser", Map.of("headed", true, "genuineVisibilityEvents", true, "focusEmulationDisabled", true, "offlineRecovery", true)); runtime.save();

    }

    @Test @Order(2) @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void recoversTransientFailureAndPreservesRetryAfterLateSupplierResponses() throws Exception {
        new IsolatedFailureScenarios(runtime, supplier).run();
    }

    @Test @Order(3) @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void recordsCurrentReadBaselineAtFixedTimelineSizes() throws Exception {
        new IsolatedReadPerformanceMatrix(runtime).run();
    }

    @Test @Order(4)
    void persistsSyntheticFeedbackAndPublishesOnlyToTheIdempotentGithubStub() throws Exception {
        assertThat(taskId).isPositive();
        runtime.token = runtime.request("POST", "/api/v1/auth/login", Map.of("account", "acceptance", "password", runtime.password), null).path("accessToken").asText();
        runtime.secrets.add(runtime.token);
        long finding = Long.parseLong(runtime.sql("SELECT id FROM review_finding WHERE task_id=" + taskId + " AND source='LLM' ORDER BY id LIMIT 1"));
        var feedback = runtime.request("POST", "/api/v1/reviews/" + taskId + "/findings/" + finding + "/feedback",
            Map.of("status", "valid", "note", "Synthetic engineering fixture; not a human quality label"), runtime.token);
        assertThat(feedback.path("findingId").asLong()).isEqualTo(finding);
        assertThat(feedback.path("feedbackStatus").asText()).isEqualToIgnoringCase("VALID");
        assertThat(supplier.requests).noneMatch(row -> row.get("method").equals("POST") && row.get("path").toString().startsWith("/repos/"));
        long firstBatch = runtime.request("POST", "/api/v1/reviews/" + taskId + "/github-comments", null, runtime.token).path("batchId").asLong();
        awaitPublication(firstBatch);
        assertThat(Integer.parseInt(runtime.sql("SELECT succeeded_count FROM github_comment_publication_batch WHERE id=" + firstBatch))).isPositive();
        int posts = githubStubPosts();
        assertThat(posts).isPositive();
        long secondBatch = runtime.request("POST", "/api/v1/reviews/" + taskId + "/github-comments", null, runtime.token).path("batchId").asLong();
        awaitPublication(secondBatch);
        assertThat(githubStubPosts()).as("Repeated publication must reuse durable publication identities").isEqualTo(posts);
        runtime.proof.put("feedback", Map.of("persisted", true, "actualHumanLabels", 0, "externalGithubPosts", 0,
            "stubGithubPublicationValidated", true, "stubGithubPosts", posts, "duplicatePosts", 0)); runtime.save();
    }

    private static int githubStubPosts() {
        return (int) supplier.requests.stream().filter(row -> row.get("method").equals("POST") && row.get("path").toString().startsWith("/repos/")).count();
    }

    private static void awaitPublication(long batch) throws Exception {
        assertThat(batch).isPositive();
        IsolatedReviewRuntime.await(() -> {
            try { return java.util.Set.of("completed", "skipped", "empty").contains(runtime.sql("SELECT status FROM github_comment_publication_batch WHERE id=" + batch)); }
            catch (Exception e) { return false; }
        }, 30);
        String result = runtime.sql("SELECT status,attempted_count,succeeded_count,failed_count,skipped_count FROM github_comment_publication_batch WHERE id=" + batch);
        Files.writeString(runtime.evidence.resolve("publication-batch-" + batch + ".tsv"), result);
        assertThat(runtime.sql("SELECT failed_count FROM github_comment_publication_batch WHERE id=" + batch)).isEqualTo("0");
    }

    @AfterAll static void cleanup() throws Exception {
        try {
            if (supplier != null) {
                Files.writeString(runtime.evidence.resolve("supplier-requests.json"), IsolatedReviewRuntime.JSON.writeValueAsString(supplier.requests));
                supplier.close();
            }
        }
        finally { if (runtime != null) runtime.close(); }
    }
}
