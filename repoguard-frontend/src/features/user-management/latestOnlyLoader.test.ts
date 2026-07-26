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

  it("keeps only the latest audit page when rapid pagination responses resolve out of order", async () => {
    let audits: number[] = [];
    let auditTotal = 0;
    const loader = createLatestOnlyLoader<{ items: number[]; total: number }>((page) => {
      audits = page.items;
      auditTotal = page.total;
    });
    const firstPage = deferred<{ items: number[]; total: number }>();
    const secondPage = deferred<{ items: number[]; total: number }>();

    const firstLoad = loader.load(() => firstPage.promise);
    const secondLoad = loader.load(() => secondPage.promise);
    secondPage.resolve({ items: [201, 202], total: 42 });
    await expect(secondLoad).resolves.toBe(true);
    firstPage.resolve({ items: [101, 102], total: 41 });
    await expect(firstLoad).resolves.toBe(false);

    expect(audits).toEqual([201, 202]);
    expect(auditTotal).toBe(42);
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
