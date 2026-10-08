import { computed, ref } from "vue";
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
  });

  it("falls back to polling when the server does not support SSE", async () => {
    vi.useFakeTimers(); vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "true");
    read.mockRejectedValue(new Error("404"));
    const { polling, poll } = setup();
    polling.startPolling();
    await vi.advanceTimersByTimeAsync(1100);
    expect(read).toHaveBeenCalledOnce();
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
    expect(read.mock.calls[1][1].signal.aborted).toBe(true);
  });

  it("suspends ordinary polling only after a real progress frame", async () => {
    vi.useFakeTimers(); vi.stubEnv("VITE_REVIEW_PROGRESS_STREAM", "true");
    read.mockImplementation(() => new Promise(() => {}));
    const { polling, poll } = setup();
    polling.startPolling();
    await read.mock.calls[0][3]("42");
    polling.syncPolling();
    await vi.advanceTimersByTimeAsync(5000);
    expect(poll).toHaveBeenCalledOnce();
  });
});
