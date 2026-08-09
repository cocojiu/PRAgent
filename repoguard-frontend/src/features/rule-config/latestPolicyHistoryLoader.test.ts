import { describe, expect, it, vi } from "vitest";
import { createLatestPolicyHistoryLoader } from "./latestPolicyHistoryLoader";

describe("latest policy history loader", () => {
  it("aborts the previous request and keeps the latest history response", async () => {
    const applied: string[] = [];
    const setLoading = vi.fn();
    const loader = createLatestPolicyHistoryLoader(setLoading);
    const first = deferred<string>();
    const second = deferred<string>();
    let firstSignal: AbortSignal | undefined;

    const firstLoad = loader.load(
      signal => {
        firstSignal = signal;
        return first.promise;
      },
      value => applied.push(value)
    );
    const secondLoad = loader.load(signal => {
      expect(signal.aborted).toBe(false);
      return second.promise;
    }, value => applied.push(value));

    expect(firstSignal?.aborted).toBe(true);
    second.resolve("latest");
    await expect(secondLoad).resolves.toBe(true);
    first.resolve("stale");
    await expect(firstLoad).resolves.toBe(false);

    expect(applied).toEqual(["latest"]);
    expect(setLoading).toHaveBeenLastCalledWith(false);
  });

  it("suppresses stale errors and invalidates pending work when cancelled", async () => {
    const apply = vi.fn();
    const setLoading = vi.fn();
    const loader = createLatestPolicyHistoryLoader(setLoading);
    const request = deferred<string>();
    let signal: AbortSignal | undefined;
    const pending = loader.load(currentSignal => {
      signal = currentSignal;
      return request.promise;
    }, apply);

    loader.cancel();
    expect(signal?.aborted).toBe(true);
    request.reject(new Error("stale history error"));

    await expect(pending).resolves.toBe(false);
    expect(apply).not.toHaveBeenCalled();
    expect(setLoading).toHaveBeenLastCalledWith(false);
  });
});

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
};
