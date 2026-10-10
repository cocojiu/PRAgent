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

  it("aborts the previous read and keeps loading until the newest read finishes", async () => {
    const loader = createLatestOnlyLoader<string>(vi.fn());
    const first = deferred<string>(); const second = deferred<string>();
    let oldSignal!: AbortSignal;
    const older = loader.load(signal => { oldSignal = signal; return first.promise; });
    const newer = loader.load(() => second.promise);
    expect(oldSignal.aborted).toBe(true);
    expect(loader.loading.value).toBe(true);
    first.reject(new Error("old error")); await older;
    expect(loader.loading.value).toBe(true);
    second.resolve("latest"); await newer;
    expect(loader.loading.value).toBe(false);
  });

  it("aborts a pending read on cancellation without applying it later", async () => {
    const apply = vi.fn(); const loader = createLatestOnlyLoader<string>(apply);
    const pending = deferred<string>(); let signal!: AbortSignal;
    const request = loader.load(value => { signal = value; return pending.promise; });
    loader.cancel();
    expect(signal.aborted).toBe(true); expect(loader.loading.value).toBe(false);
    pending.resolve("late"); await request;
    expect(apply).not.toHaveBeenCalled();
  });

  it("reports the current read failure and releases its loading state", async () => {
    const loader = createLatestOnlyLoader<string>(vi.fn());
    await expect(loader.load(() => Promise.reject(new Error("current failure")))).rejects.toThrow("current failure");
    expect(loader.loading.value).toBe(false);
  });

  it("does not start another read after disposal", async () => {
    const loader = createLatestOnlyLoader<string>(vi.fn()); const request = vi.fn();
    loader.dispose();
    await expect(loader.load(request)).resolves.toBe(false);
    expect(request).not.toHaveBeenCalled();
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
