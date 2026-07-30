import { afterEach, describe, expect, it, vi } from "vitest";
import { currentUser, loadCurrentUser, resetCurrentUser } from "@/stores/authState";
import type { CurrentUser } from "@/api/auth";

const authApi = vi.hoisted(() => ({
  getCurrentUser: vi.fn()
}));

vi.mock("@/api/auth", () => authApi);

const adminUser: CurrentUser = {
  id: 1,
  username: "admin",
  email: "admin@example.com",
  role: "ADMIN",
  status: "ACTIVE"
};

describe("auth state", () => {
  afterEach(() => {
    resetCurrentUser();
    vi.clearAllMocks();
  });

  it("shares one in-flight request across concurrent loads", async () => {
    const pending = deferred<CurrentUser>();
    authApi.getCurrentUser.mockReturnValue(pending.promise);

    const first = loadCurrentUser();
    const second = loadCurrentUser();
    pending.resolve(adminUser);

    expect(second).toBe(first);
    await expect(first).resolves.toEqual(adminUser);
    expect(authApi.getCurrentUser).toHaveBeenCalledTimes(1);
    expect(currentUser.value).toEqual(adminUser);
  });

  it("issues a new request after the previous load settles", async () => {
    authApi.getCurrentUser.mockResolvedValue(adminUser);

    await loadCurrentUser();
    await loadCurrentUser();

    expect(authApi.getCurrentUser).toHaveBeenCalledTimes(2);
  });

  it("clears the in-flight request after a failure so the next load retries", async () => {
    authApi.getCurrentUser.mockRejectedValueOnce(new Error("boom"));
    authApi.getCurrentUser.mockResolvedValueOnce(adminUser);

    await expect(loadCurrentUser()).rejects.toThrow("boom");
    await expect(loadCurrentUser()).resolves.toEqual(adminUser);

    expect(authApi.getCurrentUser).toHaveBeenCalledTimes(2);
    expect(currentUser.value).toEqual(adminUser);
  });

  it("aborts and ignores a stale admin response after the authentication epoch changes", async () => {
    const staleAdmin = deferred<CurrentUser>();
    const currentViewer = deferred<CurrentUser>();
    const viewerUser: CurrentUser = {
      ...adminUser,
      id: 2,
      username: "viewer",
      email: "viewer@example.com",
      role: "VIEWER"
    };
    authApi.getCurrentUser
      .mockReturnValueOnce(staleAdmin.promise)
      .mockReturnValueOnce(currentViewer.promise);

    const staleLoad = loadCurrentUser();
    const staleSignal = authApi.getCurrentUser.mock.calls[0][0].signal as AbortSignal;
    resetCurrentUser();
    const currentLoad = loadCurrentUser();
    currentViewer.resolve(viewerUser);
    await expect(currentLoad).resolves.toEqual(viewerUser);
    staleAdmin.resolve(adminUser);
    await expect(staleLoad).resolves.toEqual(adminUser);

    expect(staleSignal.aborted).toBe(true);
    expect(authApi.getCurrentUser).toHaveBeenCalledTimes(2);
    expect(currentUser.value).toEqual(viewerUser);
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
