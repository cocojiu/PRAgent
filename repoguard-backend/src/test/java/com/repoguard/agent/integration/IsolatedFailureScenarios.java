package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.Map;

/** Real retry commands and timed-out supplier responses; never edits business task status directly. */
final class IsolatedFailureScenarios {
    private final IsolatedReviewRuntime runtime;
    private final SupplierStub supplier;
    IsolatedFailureScenarios(IsolatedReviewRuntime runtime, SupplierStub supplier) { this.runtime = runtime; this.supplier = supplier; }

    void run() throws Exception {
        supplier.transientGithubFailures.set(1);
        long recovered = create(3, "Transient supplier recovery");
        awaitStatus(recovered, "COMPLETED");
        assertThat(supplier.transientGithubFailures.get()).isZero();
        assertThat(supplier.githubFailuresServed.get()).isEqualTo(1);
        assertThat(runtime.sql("SELECT llm_status FROM review_task WHERE id=" + recovered)).isEqualTo("COMPLETED");

        runtime.configureReviewPolicy(1, false);
        supplier.timeoutModel.set(true);
        long retried = create(2, "Timeout then retry with late supplier responses");
        awaitStatus(retried, "FAILED");
        String oldAttempt = runtime.sql("SELECT current_attempt_id FROM review_task WHERE id=" + retried);
        int delayed = supplier.delayedModelRequests.get();
        assertThat(delayed).isPositive();
        assertThat(runtime.sql("SELECT status FROM review_execution_attempt WHERE id=" + oldAttempt)).isEqualTo("FAILED");
        Files.writeString(runtime.evidence.resolve("timeout-failure.json.txt"), runtime.sql("SELECT failure_details_json FROM review_task WHERE id=" + retried));

        supplier.timeoutModel.set(false);
        runtime.configureReviewPolicy(120, true);
        runtime.request("POST", "/api/v1/reviews/" + retried + "/retry", null, runtime.token);
        awaitStatus(retried, "COMPLETED");
        String newAttempt = runtime.sql("SELECT current_attempt_id FROM review_task WHERE id=" + retried);
        assertThat(newAttempt).isNotEqualTo(oldAttempt);
        assertThat(runtime.sql("SELECT COUNT(*) FROM review_execution_attempt WHERE task_id=" + retried)).isEqualTo("2");
        String before = durableState(retried);
        supplier.releaseTimedOutModel.countDown();
        IsolatedReviewRuntime.await(() -> supplier.lateModelResponses.get() >= delayed, 10);
        Thread.sleep(6000);
        assertThat(durableState(retried)).as("Late supplier response attempts cannot overwrite the completed retry").isEqualTo(before);
        assertThat(runtime.sql("SELECT COUNT(*) FROM review_finding WHERE task_id=" + retried + " AND current_attempt=1 AND attempt_id=" + oldAttempt)).isEqualTo("0");
        runtime.proof.put("failureScenarios", Map.of("transientGithubRecovered", true, "failedTask", retried, "oldAttempt", oldAttempt, "newAttempt", newAttempt,
            "delayedSupplierRequests", delayed, "lateSupplierResponsesAttempted", supplier.lateModelResponses.get(), "retryStatePreserved", true,
            "scope", "late responses after HTTP timeout/cancellation; no claim that an old CAS write was attempted"));
        runtime.save();
    }

    private long create(int pullRequest, String title) throws Exception {
        long task = runtime.request("POST", "/api/v1/reviews/manual", Map.of("organization", "fixture", "repository", "sample", "prNumber", pullRequest,
            "title", title, "commit", SupplierStub.COMMIT, "branch", "acceptance", "source", "MANUAL"), runtime.token).path("taskId").asLong();
        assertThat(task).isPositive(); return task;
    }
    private void awaitStatus(long task, String expected) throws Exception {
        IsolatedReviewRuntime.await(() -> {
            try { return runtime.sql("SELECT status FROM review_task WHERE id=" + task).equals(expected); }
            catch (Exception e) { return false; }
        }, 40);
    }
    private String durableState(long task) throws Exception {
        return runtime.sql("SELECT status,current_attempt_id,llm_status,assessment_status FROM review_task WHERE id=" + task)
            + "\n" + runtime.sql("SELECT id,attempt_id,current_attempt,source,message FROM review_finding WHERE task_id=" + task + " ORDER BY id LIMIT 100");
    }
}
