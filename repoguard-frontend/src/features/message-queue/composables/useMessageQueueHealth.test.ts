import { createApp, nextTick, type App } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MessageQueueHealth } from "@/types";

const { fetchMessageQueueHealth, requeueMessageQueueTask, showError } = vi.hoisted(() => ({
  fetchMessageQueueHealth: vi.fn(),
  requeueMessageQueueTask: vi.fn(),
  showError: vi.fn()
}));

vi.mock("@/api/messageQueue", () => ({ fetchMessageQueueHealth, requeueMessageQueueTask }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({
  ElMessage: { error: showError, success: vi.fn(), warning: vi.fn() }
}));
vi.mock("element-plus/es/components/message-box/index.mjs", () => ({
  ElMessageBox: { confirm: vi.fn() }
}));

import { useMessageQueueHealth } from "./useMessageQueueHealth";

describe("useMessageQueueHealth", () => {
  let app: App<Element> | undefined;
  let host: HTMLDivElement | undefined;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(Math, "random").mockReturnValue(0.5);
  });

  afterEach(() => {
    app?.unmount();
    host?.remove();
    app = undefined;
    host = undefined;
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("aborts the active health request when the page is unmounted", async () => {
    const request = deferred<MessageQueueHealth>();
    fetchMessageQueueHealth.mockReturnValueOnce(request.promise);
    const state = mountComposable();
    await nextTick();

    const requestOptions = fetchMessageQueueHealth.mock.calls[0]?.[0] as { signal?: AbortSignal };
    expect(requestOptions.signal?.aborted).toBe(false);

    app?.unmount();
    app = undefined;
    expect(requestOptions.signal?.aborted).toBe(true);

    request.reject(new Error("cancelled"));
    await flushPromises();
    expect(state.errorMessage.value).toBe("");
    expect(showError).not.toHaveBeenCalled();
  });

  it("backs off background refresh failures without showing an interruptive toast", async () => {
    vi.useFakeTimers();
    fetchMessageQueueHealth
      .mockResolvedValueOnce(healthSnapshot())
      .mockRejectedValueOnce(new Error("后台刷新失败"))
      .mockResolvedValueOnce(healthSnapshot());
    const state = mountComposable();
    await flushPromises();

    state.autoRefresh.value = true;
    await nextTick();
    await vi.advanceTimersByTimeAsync(30_000);

    expect(fetchMessageQueueHealth).toHaveBeenCalledTimes(2);
    expect(state.errorMessage.value).toBe("后台刷新失败");
    expect(showError).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(59_999);
    expect(fetchMessageQueueHealth).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMessageQueueHealth).toHaveBeenCalledTimes(3);
  });

  const mountComposable = () => {
    let state!: ReturnType<typeof useMessageQueueHealth>;
    host = document.createElement("div");
    document.body.appendChild(host);
    app = createApp({
      setup() {
        state = useMessageQueueHealth();
        return () => null;
      }
    });
    app.mount(host);
    return state;
  };
});

const healthSnapshot = () => ({ exceptionTasks: [] }) as unknown as MessageQueueHealth;

const flushPromises = async () => {
  await Promise.resolve();
  await Promise.resolve();
};

const deferred = <T>() => {
  let reject!: (reason?: unknown) => void;
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
};
