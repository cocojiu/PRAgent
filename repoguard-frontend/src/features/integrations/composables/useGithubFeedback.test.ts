import { afterEach, describe, expect, it, vi } from "vitest";
import { effectScope, ref, type EffectScope } from "vue";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { GithubFeedbackDiagnostics } from "@/types";
import { useGithubFeedback } from "./useGithubFeedback";

const scopes: EffectScope[] = [];
const result = (status = "FAILED", enabled = true): GithubFeedbackDiagnostics => ({ enabled,
  events: [1, 2].map(id => ({ id, deliveryId: `delivery-${id}`, taskId: 7, findingId: 9, feedbackStatus: "false_positive", status, attempts: 1, failureCode: null, updatedAt: "2026-10-09T12:00:00Z" })) });
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const fixture = async () => {
  const requests = { fetch: vi.fn().mockResolvedValue(result()), retry: vi.fn().mockResolvedValue(undefined) };
  const canManage = ref(true); const scope = effectScope(); scopes.push(scope);
  const state = scope.run(() => useGithubFeedback({ canManage, requests }))!; await state.load();
  return { ...state, requests, canManage, scope };
};
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); });

describe("GitHub feedback request ownership", () => {
  it("forwards cancellation to the read and lets only the latest result clear loading", async () => {
    const state = await fixture(); const old = deferred<GithubFeedbackDiagnostics>(); const latest = deferred<GithubFeedbackDiagnostics>();
    state.requests.fetch.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    const first = state.load(); const signal = state.requests.fetch.mock.calls[1]![1].signal as AbortSignal;
    const second = state.load(); old.resolve(result("APPLIED")); await first;
    expect(signal.aborted).toBe(true); expect(state.loading.value).toBe(true); expect(state.diagnostics.value?.events[0]!.status).toBe("FAILED");
    latest.resolve(result("IGNORED")); await second;
    expect(state.loading.value).toBe(false); expect(state.diagnostics.value?.events[0]!.status).toBe("IGNORED");
  });

  it("ignores a stale error after a successful newer refresh", async () => {
    const state = await fixture(); const old = deferred<GithubFeedbackDiagnostics>(); state.requests.fetch.mockReturnValueOnce(old.promise);
    const first = state.load(); await state.load(); old.reject(new Error("old failure")); await first;
    expect(state.error.value).toBe(""); expect(state.stale.value).toBe(false);
  });

  it("does not retry from a loading or failed read and preserves the last record as stale", async () => {
    const state = await fixture(); const pending = deferred<GithubFeedbackDiagnostics>(); state.requests.fetch.mockReturnValueOnce(pending.promise);
    const read = state.load(); await state.retry(1); expect(state.requests.retry).not.toHaveBeenCalled();
    pending.reject(new Error("refresh unavailable")); await read; await state.retry(1);
    expect(state.error.value).toBe("refresh unavailable"); expect(state.stale.value).toBe(true); expect(state.diagnostics.value).toEqual(result());
    expect(state.requests.retry).not.toHaveBeenCalled();
  });

  it("locks retries across all rows until the write and following refresh settle", async () => {
    const state = await fixture(); const pending = deferred<void>(); const read = deferred<GithubFeedbackDiagnostics>();
    state.requests.retry.mockReturnValueOnce(pending.promise); state.requests.fetch.mockReturnValueOnce(read.promise);
    const retry = state.retry(1); await state.retry(1); await state.retry(2);
    expect(state.requests.retry).toHaveBeenCalledTimes(1); expect(state.retrying.value).toBe(1);
    pending.resolve(); await Promise.resolve(); expect(state.retrying.value).toBe(1); expect(state.canRetry(2)).toBe(false);
    read.resolve(result("PENDING")); await retry; expect(state.retrying.value).toBeNull();
    expect(state.retryMessage.value).toContain("已重新入队"); expect(state.canRetry(1)).toBe(false);
  });

  it("keeps a confirmed enqueue when its follow-up read fails", async () => {
    const state = await fixture(); state.requests.fetch.mockRejectedValueOnce(new Error("follow-up unavailable")); await state.retry(1);
    expect(state.requests.retry).toHaveBeenCalledTimes(1); expect(state.retryMessage.value).toContain("已重新入队");
    expect(state.retryError.value).toBe(""); expect(state.error.value).toBe("follow-up unavailable"); expect(state.canRetry(1)).toBe(false);
  });

  it("invalidates a read started before the enqueue completed", async () => {
    const state = await fixture(); const pending = deferred<void>(); const old = deferred<GithubFeedbackDiagnostics>();
    state.requests.retry.mockReturnValueOnce(pending.promise); const retry = state.retry(1);
    state.requests.fetch.mockReturnValueOnce(old.promise); const refresh = state.load();
    const signal = state.requests.fetch.mock.calls[1]![1].signal as AbortSignal;
    state.requests.fetch.mockResolvedValueOnce(result("PENDING")); pending.resolve(); await retry;
    old.resolve(result()); await refresh;
    expect(signal.aborted).toBe(true); expect(state.diagnostics.value?.events[0]!.status).toBe("PENDING"); expect(state.canRetry(1)).toBe(false);
  });

  it("shows write failure separately and requires a fresh read before another retry", async () => {
    const state = await fixture(); state.requests.retry.mockRejectedValueOnce(new Error("enqueue unavailable")); await state.retry(1);
    expect(state.retryError.value).toBe("enqueue unavailable"); expect(state.retryMessage.value).toBe("");
    expect(state.error.value).toBe(""); expect(state.canRetry(1)).toBe(false);
    await state.load(); expect(state.canRetry(1)).toBe(true);
  });

  it("does not use a query completed before a failed enqueue as permission to retry again", async () => {
    const state = await fixture(); const pending = deferred<void>(); state.requests.retry.mockReturnValueOnce(pending.promise);
    const retry = state.retry(1); await state.load(); expect(state.stale.value).toBe(false);
    pending.reject(new Error("enqueue unavailable")); await retry;
    expect(state.stale.value).toBe(true); expect(state.canRetry(1)).toBe(false);
  });

  it.each(["permission", "tenant", "dispose"])("ignores a late write after %s changes without running a follow-up query", async kind => {
    const state = await fixture(); const pending = deferred<void>(); state.requests.retry.mockReturnValueOnce(pending.promise);
    const retry = state.retry(1);
    if (kind === "permission") { state.canManage.value = false; state.canManage.value = true; }
    if (kind === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (kind === "dispose") state.scope.stop();
    pending.resolve(); await retry; expect(state.retryMessage.value).toBe(""); expect(state.retryError.value).toBe("");
    expect(state.diagnostics.value).toBeNull(); expect(state.requests.fetch).toHaveBeenCalledTimes(1);
  });

  it("does not start a second write after a tenant switch until the previous write has settled", async () => {
    const state = await fixture(); const pending = deferred<void>(); state.requests.retry.mockReturnValueOnce(pending.promise);
    const retry = state.retry(1); setActiveTenant("other"); await state.load(); await state.retry(2);
    expect(state.requests.retry).toHaveBeenCalledTimes(1); expect(state.retrying.value).toBe(1);
    pending.resolve(); await retry; await state.retry(2); expect(state.requests.retry).toHaveBeenCalledTimes(2);
  });

  it.each(["APPLIED", "IGNORED", "PENDING"])("does not enqueue a %s event", async status => {
    const state = await fixture(); state.requests.fetch.mockResolvedValueOnce(result(status)); await state.load();
    await state.retry(1); expect(state.requests.retry).not.toHaveBeenCalled();
  });

  it("does not enqueue absent events or when feedback is disabled", async () => {
    const state = await fixture(); await state.retry(99); state.requests.fetch.mockResolvedValueOnce(result("FAILED", false));
    await state.load(); await state.retry(1); expect(state.requests.retry).not.toHaveBeenCalled();
  });

  it("aborts a pending read on disposal and suppresses its late rejection", async () => {
    const state = await fixture(); const pending = deferred<GithubFeedbackDiagnostics>(); state.requests.fetch.mockReturnValueOnce(pending.promise);
    const read = state.load(); const signal = state.requests.fetch.mock.calls[1]![1].signal as AbortSignal;
    state.scope.stop(); expect(signal.aborted).toBe(true); pending.reject(new Error("late failure")); await read;
    expect(state.error.value).toBe(""); expect(state.diagnostics.value).toBeNull(); await state.load();
    expect(state.requests.fetch).toHaveBeenCalledTimes(2);
  });
});
