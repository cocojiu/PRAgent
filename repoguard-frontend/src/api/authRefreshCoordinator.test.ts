import { afterEach, describe, expect, it, vi } from "vitest";
import type { AuthRefreshBroadcastMessage } from "@/api/authRefreshCoordinator";
import { AuthSessionRefreshCoordinator } from "@/api/authRefreshCoordinator";
import { clearAuthToken, resolveAccessToken, saveAuthTokens } from "@/api/authSession";

class MemoryRefreshChannel {
  private readonly listeners = new Set<(event: MessageEvent) => void>();

  postMessage(message: AuthRefreshBroadcastMessage) {
    this.listeners.forEach(listener => listener(new MessageEvent("message", { data: message })));
  }

  addEventListener(_type: "message", listener: (event: MessageEvent) => void) {
    this.listeners.add(listener);
  }
}

const serializedLock = () => {
  let tail = Promise.resolve();
  return async (task: () => Promise<boolean>) => {
    const previous = tail;
    let release!: () => void;
    tail = new Promise<void>(resolve => {
      release = resolve;
    });
    await previous;
    try {
      return await task();
    } finally {
      release();
    }
  };
};

const successfulRefresh = (accessToken: string) => Promise.resolve({
  ok: true,
  body: {
    success: true,
    code: "OK",
    message: "success",
    data: { accessToken },
    timestamp: "2026-07-18T19:30:00+08:00"
  }
});

describe("AuthSessionRefreshCoordinator", () => {
  afterEach(() => {
    clearAuthToken();
    window.sessionStorage.clear();
    window.localStorage.clear();
  });

  it("uses one refresh across coordinators sharing a browser lock", async () => {
    saveAuthTokens("expired-access", "", true);
    const channel = new MemoryRefreshChannel();
    const runWithCrossTabLock = serializedLock();
    const firstRefresh = vi.fn(() => successfulRefresh("shared-access"));
    const secondRefresh = vi.fn(() => successfulRefresh("unexpected-access"));
    let clock = 100;
    const options = {
      channel,
      runWithCrossTabLock,
      now: () => ++clock
    };
    const first = new AuthSessionRefreshCoordinator(firstRefresh, options);
    const second = new AuthSessionRefreshCoordinator(secondRefresh, options);

    await expect(Promise.all([first.refreshSession(), second.refreshSession()]))
      .resolves.toEqual([true, true]);

    expect(firstRefresh).toHaveBeenCalledTimes(1);
    expect(secondRefresh).not.toHaveBeenCalled();
    expect(resolveAccessToken()).toBe("shared-access");
  });

  it("uses a concurrent success when Web Locks are unavailable", async () => {
    saveAuthTokens("expired-access", "", true);
    const channel = new MemoryRefreshChannel();
    let releaseFirst!: (value: Awaited<ReturnType<typeof successfulRefresh>>) => void;
    const firstResponse = new Promise<Awaited<ReturnType<typeof successfulRefresh>>>(resolve => {
      releaseFirst = resolve;
    });
    const first = new AuthSessionRefreshCoordinator(() => firstResponse, {
      channel,
      runWithCrossTabLock: null,
      crossTabResultWaitMs: 100
    });
    const second = new AuthSessionRefreshCoordinator(async () => ({ ok: false }), {
      channel,
      runWithCrossTabLock: null,
      crossTabResultWaitMs: 100
    });

    const firstResult = first.refreshSession();
    const secondResult = second.refreshSession();
    await Promise.resolve();
    releaseFirst(await successfulRefresh("fallback-shared-access"));

    await expect(Promise.all([firstResult, secondResult])).resolves.toEqual([true, true]);
    expect(resolveAccessToken()).toBe("fallback-shared-access");
  });

  it("does not restore a session from a broadcast after local logout", () => {
    saveAuthTokens("access", "", true);
    const channel = new MemoryRefreshChannel();
    new AuthSessionRefreshCoordinator(async () => ({ ok: false }), {
      channel,
      runWithCrossTabLock: null
    });
    clearAuthToken();

    channel.postMessage({
      type: "auth-refresh-completed",
      completedAt: Date.now(),
      success: true,
      accessToken: "test-must-not-be-restored"
    });

    expect(resolveAccessToken()).toBe("");
  });
});
