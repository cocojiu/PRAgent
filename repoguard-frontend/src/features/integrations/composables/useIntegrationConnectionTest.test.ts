import { afterEach, describe, expect, it, vi } from "vitest";
import { effectScope, reactive, ref, type EffectScope } from "vue";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { ConnectionTestResult, IntegrationConfig } from "@/types";
import { useIntegrationConnectionTest } from "./useIntegrationConnectionTest";

const messages = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: messages }));
const scopes: EffectScope[] = [];
const result = (success = true): ConnectionTestResult => ({ success, status: success ? "connected" : "failed", message: success ? "Connected" : "Failed", checkedAt: "2026-10-09T12:00:00Z" });
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const fixture = () => {
  const config = reactive<Record<string, unknown>>({ baseUrl: "https://example.test", token: "mock-only-token", timeoutSeconds: 60 });
  const canManage = ref(true); const testingConnections = reactive<Record<string, boolean>>({});
  const apply = vi.fn(); const test = vi.fn().mockResolvedValue(result());
  const scope = effectScope(); scopes.push(scope);
  const state = scope.run(() => useIntegrationConnectionTest({ canManage, captureConfig: () => config,
    hasIntegration: id => id === "github", applyConnectionTestResult: apply, testActions: { github: test }, testingConnections }))!;
  return { ...state, config, canManage, testingConnections, apply, test, scope };
};
const card = (): IntegrationConfig => ({ id: "github", name: "GitHub", description: "", status: "connected", statusText: "已连接", message: "previous", metaLabel: "检测时间", metaValue: "previous time", fields: [], diagnostics: [{ label: "previous diagnostic", value: "healthy" }] });
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); vi.clearAllMocks(); });

describe("connection test configuration binding", () => {
  it.each([true, false])("applies a %s result only for the configuration that was tested", async success => {
    const state = fixture(); state.test.mockResolvedValueOnce(result(success)); await state.testConnection("github");
    expect(state.apply).toHaveBeenCalledWith("github", result(success)); expect(state.testingConnections.github).toBe(false);
    expect(success ? messages.success : messages.error).toHaveBeenCalledWith(result(success).message);
  });

  it("labels an edited configuration as unverified instead of connected", async () => {
    const state = fixture(); await state.testConnection("github"); state.config.baseUrl = "https://another.test";
    const displayed = state.displayConnection(card()); expect(displayed.status).toBe("pending");
    expect(displayed.statusText).toBe("待重新测试"); expect(displayed.message).toContain("上次结果不能确认");
    expect(displayed.metaLabel).toBe("上次检测时间"); expect(displayed.diagnostics).toBeUndefined();
  });

  it("also marks a draft edited before its first test as unverified", () => {
    const state = fixture(); expect(state.displayConnection(card(), true).statusText).toBe("待重新测试");
    expect(state.displayConnection(card()).statusText).toBe("已连接");
  });

  it.each(["success", "failure"])("ignores a late %s for inputs edited during the request", async kind => {
    const state = fixture(); const pending = deferred<ConnectionTestResult>(); state.test.mockReturnValueOnce(pending.promise);
    const call = state.testConnection("github"); expect(state.displayConnection(card()).statusText).toBe("检测中");
    state.config.token = "new-mock-token";
    if (kind === "success") pending.resolve(result()); else pending.reject(new Error("old failure"));
    await call; expect(state.apply).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled(); expect(messages.error).not.toHaveBeenCalled();
    expect(state.displayConnection(card()).statusText).toBe("待重新测试");
    state.config.token = "mock-only-token";
    expect(state.displayConnection(card()).statusText).toBe("待重新测试");
  });

  it("includes hidden policy fields in configuration matching", async () => {
    const state = fixture(); await state.testConnection("github"); state.config.timeoutSeconds = 90;
    expect(state.displayConnection(card()).status).toBe("pending");
  });

  it("blocks a duplicate request until the original settles and allows a new test afterwards", async () => {
    const state = fixture(); const pending = deferred<ConnectionTestResult>(); state.test.mockReturnValueOnce(pending.promise);
    const call = state.testConnection("github"); state.config.baseUrl = "https://another.test";
    await state.testConnection("github"); expect(state.test).toHaveBeenCalledTimes(1);
    pending.resolve(result()); await call; await state.testConnection("github");
    expect(state.test).toHaveBeenCalledTimes(2); expect(state.apply).toHaveBeenCalledTimes(1);
    expect(state.displayConnection(card()).statusText).toBe("已连接");
  });

  it.each(["permission", "tenant", "dispose"])("ignores a pending result after %s changes, including a switch back", async kind => {
    const state = fixture(); const pending = deferred<ConnectionTestResult>(); state.test.mockReturnValueOnce(pending.promise);
    const call = state.testConnection("github");
    if (kind === "permission") { state.canManage.value = false; state.canManage.value = true; }
    if (kind === "tenant") { setActiveTenant("other"); clearActiveTenant(); }
    if (kind === "dispose") state.scope.stop();
    pending.resolve(result()); await call;
    expect(state.apply).not.toHaveBeenCalled(); expect(messages.success).not.toHaveBeenCalled();
    if (kind !== "dispose") expect(state.displayConnection(card()).statusText).toBe("待重新测试");
  });

  it("reports a current test rejection and unlocks the test", async () => {
    const state = fixture(); state.test.mockRejectedValueOnce(new Error("current failure")); await state.testConnection("github");
    expect(state.apply).toHaveBeenCalledWith("github", expect.objectContaining({ success: false, message: "current failure" }));
    expect(messages.error).toHaveBeenCalledWith("current failure"); expect(state.testingConnections.github).toBe(false);
  });

  it("does not start a test without management permission or after disposal", async () => {
    const state = fixture(); state.canManage.value = false; await state.testConnection("github");
    state.canManage.value = true; state.scope.stop(); await state.testConnection("github");
    expect(state.test).not.toHaveBeenCalled(); expect(state.apply).not.toHaveBeenCalled();
  });

  it("keeps private configuration snapshots out of browser storage", async () => {
    const state = fixture(); const store = vi.spyOn(Storage.prototype, "setItem"); await state.testConnection("github");
    expect(store).not.toHaveBeenCalled(); store.mockRestore();
  });
});
