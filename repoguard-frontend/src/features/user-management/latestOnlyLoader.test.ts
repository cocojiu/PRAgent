import { describe, expect, it, vi } from "vitest";
import { createLatestOnlyLoader } from "./latestOnlyLoader";

describe("latest-only loader", () => {
  it("ignores an older response that arrives after the latest request", async () => {
    const applied: string[] = [];
    const loader = createLatestOnlyLoader<string>((value) => applied.push(value));
    const first = deferred<string>();
    const second = deferred<string>();

    const firstLoad = loader.load(() => first.promise);
    const secondLoad = loader.load(() => second.promise);
    second.resolve("new");
    await expect(secondLoad).resolves.toBe(true);
    first.resolve("old");
    await expect(firstLoad).resolves.toBe(false);

    expect(applied).toEqual(["new"]);
  });

  it("suppresses stale errors and invalidates requests on cancel", async () => {
    const apply = vi.fn();
    const loader = createLatestOnlyLoader<string>(apply);
    const request = deferred<string>();
    const loading = loader.load(() => request.promise);

    loader.cancel();
    request.reject(new Error("stale"));

    await expect(loading).resolves.toBe(false);
    expect(apply).not.toHaveBeenCalled();
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
