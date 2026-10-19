package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    }

    @Test @Order(1) @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realLongReviewSurvivesNativeBackgroundAndNetworkRecovery() throws Exception {
        taskId = runtime.request("POST", "/api/v1/reviews/manual", Map.of("organization", "fixture", "repository", "sample",
            "prNumber", 1, "title", "Isolated long review", "commit", SupplierStub.COMMIT, "branch", "acceptance", "source", "MANUAL"), runtime.token).path("taskId").asLong();
        assertThat(taskId).isPositive();
        assertThat(supplier.llmEntered.await(45, TimeUnit.SECONDS)).as("Real worker must reach the isolated HTTPS model boundary").isTrue();
        Instant heldAt = Instant.now();
        assertThat(runtime.sql("SELECT COUNT(*) FROM review_execution_attempt WHERE task_id=" + taskId)).isEqualTo("1");
        String attempt = runtime.sql("SELECT current_attempt_id FROM review_task WHERE id=" + taskId);
        runtime.proof.put("taskId", taskId); runtime.proof.put("attemptId", attempt); runtime.save();
        runtime.request("POST", "/api/v1/auth/register", Map.of("username", "subscriber", "email", "subscriber@example.test", "password", runtime.password, "confirmPassword", runtime.password), null);
        String subscriberToken = runtime.request("POST", "/api/v1/auth/login", Map.of("account", "subscriber", "password", runtime.password), null).path("accessToken").asText();
        runtime.secrets.add(subscriberToken);
        var transport = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        try (var firstSubscriber = new IsolatedSseSubscriber(runtime, taskId, subscriberToken, "0");
             var futureCursorSubscriber = new IsolatedSseSubscriber(runtime, taskId, subscriberToken, Long.toString(Long.MAX_VALUE));
             Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false))) {
            firstSubscriber.awaitProgress(); futureCursorSubscriber.awaitProgress();
            var context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
            // Listen to genuine events only: no property replacement and no synthetic dispatch.
            context.addInitScript("window.acceptanceVisibility = [{state:document.visibilityState,at:Date.now()}]; document.addEventListener('visibilitychange',()=>window.acceptanceVisibility.push({state:document.visibilityState,at:Date.now()}));");
            Page page = context.newPage();
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
            Page background = context.newPage(); background.navigate("about:blank"); background.bringToFront();
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
            supplier.releaseLlm.countDown();
            IsolatedReviewRuntime.await(() -> {
                try { return runtime.sql("SELECT status FROM review_task WHERE id=" + taskId).matches("COMPLETED|PENDING_HUMAN_REVIEW"); }
                catch (Exception e) { return false; }
            }, 45);
            page.waitForTimeout(6_000);
            page.screenshot(new Page.ScreenshotOptions().setPath(runtime.evidence.resolve("completed-review.png")));
            assertThat(page.locator("body").innerText()).contains("Isolated long review");
            page.navigate(runtime.baseUrl + "/repoguard/tasks"); page.waitForTimeout(6_000);
            context.close();
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
        runtime.proof.put("longReview", Map.of("passed", true, "heldSeconds", Duration.between(heldAt, Instant.now()).toSeconds(), "llmCalls", supplier.llmCalls.get(), "duplicateTaskReused", true));
        runtime.proof.put("browser", Map.of("headed", true, "genuineVisibilityEvents", true, "offlineRecovery", true)); runtime.save();
    }

    @Test @Order(2) @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void recordsCurrentReadBaselineAtFixedTimelineSizes() throws Exception {
        long baselineTask = 900001;
        runtime.sql("INSERT INTO review_task(id,tenant_id,organization,repository,pr_number,title,commit_sha,branch_name,status,risk_level,llm_status,created_at,finished_at) "
            + "VALUES (900001,1,'fixture','baseline',900001,'Synthetic read baseline','" + SupplierStub.COMMIT + "','main','COMPLETED','LOW','SKIPPED',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        var all = new ArrayList<Map<String, Object>>();
        // API layer only: the proxy has separate rate limits, so edge bytes/latency remain unmeasured here.
        String apiIp = runtime.inspect("container", runtime.api).path("NetworkSettings").path("Networks").path(runtime.owner).path("IPAddress").asText();
        String endpoint = "http://" + apiIp + ":8081/api/v1/reviews/" + baselineTask + "/status";
        runtime.enableSqlObservation();
        int previous = 0;
        for (int size : new int[] {10, 1000, 10000, 50000}) {
            if (size > previous) {
                StringBuilder sql = new StringBuilder("INSERT INTO review_timeline(tenant_id,task_id,label,event_time,status,sort_order) VALUES ");
                for (int index = previous; index < size; index++) {
                    if (index > previous) sql.append(',');
                    sql.append("(1,").append(baselineTask).append(",'isolated scale event',UTC_TIMESTAMP(),'DONE',").append(index).append(')');
                }
                runtime.sql(sql.toString()); previous = size;
            }
            assertThat(runtime.sql("SELECT COUNT(*) FROM review_timeline WHERE task_id=" + baselineTask)).isEqualTo(Integer.toString(size));
            for (int concurrency : new int[] {1, 2, 4}) {
                for (int warmup = 0; warmup < 10; warmup++) measure(endpoint);
                runtime.captureReadCounters("rows-" + size + "-c" + concurrency + "-before", baselineTask);
                var samples = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
                try (var executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency)) {
                    var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                    for (int index = 0; index < 200; index++) jobs.add(executor.submit(() -> {
                        try { samples.add(measure(endpoint)); } catch (Exception e) { samples.add(Map.of("status", 0, "error", e.getClass().getSimpleName())); }
                    }));
                    for (var job : jobs) job.get(60, TimeUnit.SECONDS);
                }
                runtime.captureReadCounters("rows-" + size + "-c" + concurrency + "-after", baselineTask);
                var durations = samples.stream().filter(row -> row.get("status").equals(200)).map(row -> (Long) row.get("durationNanos")).sorted().toList();
                var summary = new LinkedHashMap<String, Object>();
                summary.put("rows", size); summary.put("concurrency", concurrency); summary.put("attempts", samples.size()); summary.put("successes", durations.size());
                if (!durations.isEmpty()) { summary.put("p50Ms", durations.get((durations.size() - 1) / 2) / 1_000_000.0); summary.put("p95Ms", durations.get((int) Math.ceil(durations.size() * .95) - 1) / 1_000_000.0); }
                summary.put("samples", samples); all.add(summary);
                Files.writeString(runtime.evidence.resolve("status-api-baseline.json"), IsolatedReviewRuntime.JSON.writeValueAsString(all));
                assertThat(samples).hasSize(200); assertThat(durations).hasSize(200);
            }
        }
        runtime.proof.put("performance", Map.of("scope", "current status API baseline only", "observations", 2400, "beforeAfterBenefitProven", false,
            "uncovered", List.of("list/summary/detail", "failed/archive shapes", "pre-CODE032 build", "production representative distribution"))); runtime.save();
    }

    private static Map<String, Object> measure(String endpoint) throws Exception {
        long started = System.nanoTime();
        var response = runtime.http.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + runtime.token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        long elapsed = System.nanoTime() - started;
        return Map.of("status", response.statusCode(), "durationNanos", elapsed, "applicationBytes", response.body().length);
    }

    @Test @Order(3)
    void persistsSyntheticFeedbackAndPublishesOnlyToTheIdempotentGithubStub() throws Exception {
        assertThat(taskId).isPositive();
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
