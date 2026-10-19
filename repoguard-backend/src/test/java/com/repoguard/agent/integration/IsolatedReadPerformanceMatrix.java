package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Fixed synthetic API matrix. Archived event counts are metadata, never pretended to be hot timeline rows. */
final class IsolatedReadPerformanceMatrix {
    private final IsolatedReviewRuntime runtime;
    private final List<Map<String, Object>> summaries = new ArrayList<>();
    private int observations;
    IsolatedReadPerformanceMatrix(IsolatedReviewRuntime runtime) { this.runtime = runtime; }

    void run() throws Exception {
        runtime.enableSqlObservation();
        String apiIp = runtime.inspect("container", runtime.api).path("NetworkSettings").path("Networks").path(runtime.owner).path("IPAddress").asText();
        String base = "http://" + apiIp + ":8081/api/v1/reviews";
        try (BufferedWriter csv = Files.newBufferedWriter(runtime.evidence.resolve("read-api-samples.csv"))) {
            csv.write("shape,timeline_count,concurrency,endpoint,index,http_status,valid,duration_nanos,application_bytes,error,trace_id\n");
            int ordinal = 0;
            for (String shape : List.of("completed", "failed-legacy", "failed-structured", "archived")) {
                long task = 900001L + ordinal++;
                seedTask(task, shape);
                int previous = 0;
                for (int size : new int[] {10, 1000, 10000, 50000}) {
                    seedTimeline(task, shape, previous, size); previous = size;
                    List<Endpoint> endpoints = new ArrayList<>(List.of(new Endpoint("status", base + "/" + task + "/status"), new Endpoint("detail", base + "/" + task)));
                    // Hot list/summary endpoints deliberately exclude archive summaries.
                    if (!shape.equals("archived")) {
                        endpoints.add(new Endpoint("list", base + "?keyword=performance-" + shape));
                        endpoints.add(new Endpoint("summary", base + "/summary?keyword=performance-" + shape));
                    }
                    for (int concurrency : new int[] {1, 2, 4}) {
                        String stage = shape + "-rows" + size + "-c" + concurrency;
                        for (Endpoint endpoint : endpoints) for (int warmup = 0; warmup < 10; warmup++) assertThat(measure(endpoint, task, shape).valid()).isTrue();
                        runtime.captureReadCounters(stage + "-before", task);
                        for (Endpoint endpoint : endpoints) {
                            runtime.captureSqlCounters(stage + "-" + endpoint.name() + "-before");
                            List<Sample> samples = observe(endpoint, task, shape, concurrency);
                            runtime.captureSqlCounters(stage + "-" + endpoint.name() + "-after");
                            int index = 0;
                            for (Sample sample : samples) csv.write(shape + "," + size + "," + concurrency + "," + endpoint.name() + "," + index++ + "," + sample.status() + "," + sample.valid() + "," + sample.nanos() + "," + sample.bytes() + "," + sample.error() + "," + sample.trace() + "\n");
                            csv.flush(); observations += samples.size();
                            List<Long> durations = samples.stream().filter(Sample::valid).map(Sample::nanos).sorted().toList();
                            summaries.add(Map.of("shape", shape, "timelineCount", size, "concurrency", concurrency, "endpoint", endpoint.name(), "attempts", samples.size(), "valid", durations.size(),
                                "p50Ms", durations.isEmpty() ? -1 : durations.get((durations.size() - 1) / 2) / 1_000_000.0,
                                "p95Ms", durations.isEmpty() ? -1 : durations.get((int) Math.ceil(durations.size() * .95) - 1) / 1_000_000.0));
                            Files.writeString(runtime.evidence.resolve("read-api-summary.json"), IsolatedReviewRuntime.JSON.writeValueAsString(summaries));
                            assertThat(samples).hasSize(200); assertThat(durations).hasSize(200);
                        }
                        runtime.captureReadCounters(stage + "-after", task);
                    }
                }
            }
        }
        assertThat(observations).isEqualTo(33600);
        runtime.proof.put("performance", Map.of("scope", "current API matrix only; proxy latency/bytes excluded", "observations", observations,
            "warmupPerEndpointStage", 10, "archivePhysicalTimelineRows", 0, "beforeAfterBenefitProven", false,
            "uncovered", List.of("pre-CODE032 runtime comparison", "cold-start matrix", "production representative distribution")));
        runtime.save();
    }

    private void seedTask(long task, String shape) throws Exception {
        if (shape.equals("archived")) {
            runtime.sql("INSERT INTO review_task_archive_summary(tenant_id,task_id,cleanup_batch_id,organization,repository,pr_number,title,commit_sha,branch_name,status,risk_level,created_at,finished_at,backup_reference) VALUES "
                + "(1," + task + ",900000,'fixture','baseline'," + task + ",'performance-archived','" + SupplierStub.COMMIT + "','main','COMPLETED','LOW',UTC_TIMESTAMP(),UTC_TIMESTAMP(),'synthetic-baseline-only')");
        } else {
            runtime.sql("INSERT INTO review_task(id,tenant_id,organization,repository,pr_number,title,commit_sha,branch_name,status,risk_level,llm_status,created_at,finished_at,failure_details_json) VALUES "
                + "(" + task + ",1,'fixture','baseline'," + task + ",'performance-" + shape + "','" + SupplierStub.COMMIT + "','main','"
                + (shape.startsWith("failed") ? "FAILED" : "COMPLETED") + "','LOW','SKIPPED',UTC_TIMESTAMP(),UTC_TIMESTAMP(),"
                + (shape.equals("failed-structured") ? "JSON_OBJECT('category','review_timeout','retryable',CAST('true' AS JSON),'stage','REVIEW_EXECUTION','attemptId',NULL,'retryAfter','')" : "NULL") + ")");
        }
    }

    private void seedTimeline(long task, String shape, int previous, int size) throws Exception {
        if (shape.equals("archived")) {
            runtime.sql("UPDATE review_task_archive_summary SET timeline_count=" + size + " WHERE task_id=" + task);
            assertThat(runtime.sql("SELECT COUNT(*) FROM review_timeline WHERE task_id=" + task)).isEqualTo("0"); return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO review_timeline(tenant_id,task_id,label,event_time,status,sort_order) VALUES ");
        for (int index = previous; index < size; index++) {
            if (index > previous) sql.append(',');
            String label = shape.equals("failed-legacy") && index == 5 ? "Review failed: category=llm_timeout" : "isolated scale event";
            sql.append("(1,").append(task).append(",'").append(label).append("',UTC_TIMESTAMP(),'DONE',").append(index).append(')');
        }
        runtime.sql(sql.toString());
        assertThat(runtime.sql("SELECT COUNT(*) FROM review_timeline WHERE task_id=" + task)).isEqualTo(Integer.toString(size));
    }

    private List<Sample> observe(Endpoint endpoint, long task, String shape, int concurrency) throws Exception {
        var executor = Executors.newFixedThreadPool(concurrency);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Sample>>();
            for (int index = 0; index < 200; index++) futures.add(executor.submit(() -> measure(endpoint, task, shape)));
            var samples = new ArrayList<Sample>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            for (var future : futures) samples.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            return samples;
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private Sample measure(Endpoint endpoint, long task, String shape) {
        long started = System.nanoTime();
        try {
            var response = runtime.http.send(HttpRequest.newBuilder(URI.create(endpoint.url())).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + runtime.token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            long elapsed = System.nanoTime() - started; // Parsing/validation is intentionally outside HTTP attempt duration.
            var root = IsolatedReviewRuntime.JSON.readTree(response.body());
            var data = root.path("data");
            boolean valid = response.statusCode() == 200 && root.path("success").asBoolean();
            if (endpoint.name().equals("status") || endpoint.name().equals("detail")) valid &= data.path("id").asLong() == task;
            else valid &= data.path("total").asLong(-1) == 1;
            if (endpoint.name().equals("detail") && shape.equals("archived")) valid &= data.path("archived").asBoolean();
            String trace = response.headers().firstValue("X-Trace-Id").orElse("");
            assertThat(trace).matches("[A-Za-z0-9-]{0,64}");
            return new Sample(response.statusCode(), valid, elapsed, response.body().length, valid ? "" : "response_contract", trace);
        } catch (Exception | AssertionError error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Sample(0, false, System.nanoTime() - started, 0, error.getClass().getSimpleName(), "");
        }
    }
    private record Endpoint(String name, String url) { }
    private record Sample(int status, boolean valid, long nanos, int bytes, String error, String trace) { }
}
