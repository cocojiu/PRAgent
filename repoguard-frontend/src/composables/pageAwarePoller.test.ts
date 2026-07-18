import { afterEach, describe, expect, it, vi } from "vitest";
import { createPageAwarePoller } from "./pageAwarePoller";

describe("createPageAwarePoller", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("waits for an active request before scheduling the next poll", async () => {
    vi.useFakeTimers();
    const firstRequest = deferred<void>();
    const poll = vi.fn()
      .mockReturnValueOnce(firstRequest.promise)
      .mockResolvedValue(undefined);
    const poller = createPageAwarePoller({
      intervalMs: () => 1_000,
      isEnabled: () => true,
      jitterRatio: 0,
      poll
    });

    poller.start();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(poll).toHaveBeenCalledOnce();

    await vi.advanceTimersByTimeAsync(5_000);
    expect(poll).toHaveBeenCalledOnce();

    firstRequest.resolve();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(999);
    expect(poll).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(1);
    expect(poll).toHaveBeenCalledTimes(2);

    poller.dispose();
  });

  it("pauses while the page is hidden or offline and resumes on availability events", async () => {
    vi.useFakeTimers();
    let pageVisible = false;
    let online = true;
    const poll = vi.fn().mockResolvedValue(undefined);
    const poller = createPageAwarePoller({
      intervalMs: () => 1_000,
      isEnabled: () => true,
      isOnline: () => online,
      isPageVisible: () => pageVisible,
      jitterRatio: 0,
      poll
    });

    poller.start();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(poll).not.toHaveBeenCalled();

    pageVisible = true;
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(1_000);
    expect(poll).toHaveBeenCalledOnce();

    online = false;
    window.dispatchEvent(new Event("offline"));
    await vi.advanceTimersByTimeAsync(2_000);
    expect(poll).toHaveBeenCalledOnce();

    online = true;
    window.dispatchEvent(new Event("online"));
    await vi.advanceTimersByTimeAsync(1_000);
    expect(poll).toHaveBeenCalledTimes(2);

    poller.dispose();
  });

  it("adds bounded jitter to the configured interval", async () => {
    vi.useFakeTimers();
    const poll = vi.fn().mockResolvedValue(undefined);
    const poller = createPageAwarePoller({
      intervalMs: () => 1_000,
      isEnabled: () => true,
      jitterRatio: 0.1,
      poll,
      random: () => 1
    });

    poller.start();
    await vi.advanceTimersByTimeAsync(1_099);
    expect(poll).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(poll).toHaveBeenCalledOnce();

    poller.dispose();
  });
});

const deferred = <T>() => {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve;
  });
  return { promise, resolve };
};
