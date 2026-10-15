import { computed, nextTick, ref } from "vue";
import { setActiveTenant } from "@/stores/tenantContext";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useReviewDetailPolling } from "./useReviewDetailPolling";
import { readReviewProgressStream } from "../reviewProgressStream";

vi.mock("../reviewProgressStream", () => ({ readReviewProgressStream: vi.fn() }));
const read = vi.mocked(readReviewProgressStream);
const disposers: (() => void)[] = [];
const setup = () => {
  const poll = vi.fn().mockResolvedValue(undefined);
  const task = ref(9);
  const active = ref(true);
  const polling = useReviewDetailPolling({
    currentPollIntervalMs: computed(() => 1000), maxPollFailures: 5, pollFailureCount: ref(0),
    pollReviewStatus: poll, shouldPollTask: computed(() => active.value), getTaskId: () => task.value
  });
  disposers.push(polling.cleanupPolling);
  return { poll, task, active, polling };
};

describe("detail progress stream fallback", () => {
  afterEach(() => {
    for (const dispose of disposers.splice(0)) dispose();
    setActiveTenant();
    vi.unstubAllEnvs(); vi.restoreAllMocks(); vi.resetAllMocks(); vi.useRealTimers();
  });

  it("keeps ordinary polling when the feature is disabled", async () => {
    vi.useFakeTimers();
    vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "false");
    const { polling, poll } = setup();
    polling.startPolling();
    await vi.advanceTimersByTimeAsync(1100);
    expect(poll).toHaveBeenCalledOnce();
    expect(read).not.toHaveBeenCalled();
    expect(polling.connectionState.value).toBe("polling");
  });

  it("falls back to polling when the server does not support SSE", async () => {
    vi.useFakeTimers(); vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "true");
    read.mockRejectedValue(new Error("404"));
    const { polling, poll } = setup();
    polling.startPolling();
    await vi.advanceTimersByTimeAsync(1100);
    expect(read).toHaveBeenCalledOnce();
    expect(polling.connectionState.value).toBe("backoff");
    expect(poll).toHaveBeenCalledOnce();
  });

  it("ignores events for the previous route and aborts on cleanup", async () => {
    vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "true");
    read.mockImplementation(() => new Promise(() => {}));
    const { polling, poll, task } = setup();
    polling.startPolling();
    const first = read.mock.calls[0];
    task.value = 10;
    polling.stopPolling(); polling.startPolling();
    await first[3]("42");
    expect(poll).not.toHaveBeenCalled();
    expect(first[1].signal.aborted).toBe(true);
    polling.cleanupPolling();
    expect(polling.connectionState.value).toBe("disposed");
    expect(read.mock.calls[1][1].signal.aborted).toBe(true);
  });

  it("aborts on hidden/offline and reconnects in the current tenant on resume", async () => {
    vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "true");
    read.mockImplementation(() => new Promise(() => {}));
    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    const online = vi.spyOn(navigator, "onLine", "get").mockReturnValue(true);
    const { polling } = setup();
    polling.startPolling();
    expect(polling.connectionState.value).toBe("connecting");
    const first = read.mock.calls[0];
    visibility.mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    expect(first[1].signal.aborted).toBe(true);
    expect(polling.connectionState.value).toBe("paused");
    setActiveTenant("another-tenant"); await nextTick();
    expect(read).toHaveBeenCalledTimes(1);
    visibility.mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    expect(read).toHaveBeenCalledTimes(2);
    expect(read.mock.calls[1][2]).toBe("");
    online.mockReturnValue(false);
    window.dispatchEvent(new Event("offline"));
    expect(read.mock.calls[1][1].signal.aborted).toBe(true);
    expect(polling.connectionState.value).toBe("paused");
    polling.cleanupPolling();
    online.mockReturnValue(true);
    window.dispatchEvent(new Event("online"));
    expect(read).toHaveBeenCalledTimes(2);
  });

  it("bounds short reconnect failures and resets the budget for a new task", async () => {
    vi.useFakeTimers(); vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "true");
    read.mockRejectedValue(new Error("closed"));
    const { polling, task } = setup();
    polling.startPolling();
    await vi.advanceTimersByTimeAsync(0);
    for (let i = 0; i < 2; i++) {
      await vi.advanceTimersByTimeAsync(30000);
      polling.syncPolling();
      await vi.advanceTimersByTimeAsync(0);
    }
    expect(read).toHaveBeenCalledTimes(3);
    expect(polling.connectionState.value).toBe("polling");
    await vi.advanceTimersByTimeAsync(30000);
    polling.syncPolling();
    expect(read).toHaveBeenCalledTimes(3);
    task.value = 10;
    polling.stopPolling(); polling.startPolling();
    expect(read).toHaveBeenCalledTimes(4);
    expect(read.mock.calls[3][0]).toBe(10);
  });

  it("suspends ordinary polling only after a real progress frame", async () => {
    vi.useFakeTimers(); vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "true");
    read.mockImplementation(() => new Promise(() => {}));
    const { polling, poll } = setup();
    polling.startPolling();
    await read.mock.calls[0][3]("42");
    expect(polling.connectionState.value).toBe("connected");
    polling.syncPolling();
    await vi.advanceTimersByTimeAsync(5000);
    expect(poll).toHaveBeenCalledOnce();
  });
});
