import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { effectScope, ref, type EffectScope } from "vue";
import { clearActiveTenant, setActiveTenant } from "@/stores/tenantContext";
import type { NotificationEvent, NotificationDelivery } from "@/types";
import { useNotificationOpsRecords } from "./useNotificationOpsRecords";

const mocks = vi.hoisted(() => ({ events: vi.fn(), deliveries: vi.fn(), retry: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock("@/api/config", () => ({ fetchNotificationEvents: mocks.events, fetchNotificationDeliveries: mocks.deliveries, retryNotificationEvent: mocks.retry }));
vi.mock("element-plus/es/components/message/index.mjs", () => ({ ElMessage: mocks }));
const scopes: EffectScope[] = [];
const page = (id = 1, total = 25) => ({ items: [{ id, status: "FAILED", retryCount: 1, eventKey: "test-event", eventType: "REVIEW_FAILED", taskId: 7,
  eventId: 9, bindingId: 2, provider: "EMAIL", attemptCount: 1 }] as Array<NotificationEvent & NotificationDelivery>, total });
const deferred = <T>() => {
  let resolve!: (value: T) => void; let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
};
const setup = () => {
  const scope = effectScope(); scopes.push(scope); const canManage = ref(true); const bindings = vi.fn().mockResolvedValue(undefined);
  const state = scope.run(() => useNotificationOpsRecords({ canManage, loadNotificationBindings: bindings }))!;
  return { ...state, scope, canManage, bindings };
};
beforeEach(() => { vi.resetAllMocks(); mocks.events.mockResolvedValue(page()); mocks.deliveries.mockResolvedValue(page()); mocks.retry.mockResolvedValue(undefined); });
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); clearActiveTenant(); });

describe.each(["events", "deliveries"] as const)("notification %s query ownership", kind => {
  const select = (state: ReturnType<typeof setup>) => kind === "events"
    ? { load: state.loadNotificationEvents, rows: state.notificationEvents, total: state.notificationEventTotal, filter: state.eventFilter,
      loading: state.eventsLoading, error: state.eventsError, stale: state.eventsNeedsRefresh, request: mocks.events }
    : { load: state.loadNotificationDeliveries, rows: state.notificationDeliveries, total: state.notificationDeliveryTotal, filter: state.deliveryFilter,
      loading: state.deliveriesLoading, error: state.deliveriesError, stale: state.deliveriesNeedsRefresh, request: mocks.deliveries };

  it("cancels an older query and accepts only the latest rows, total and loading completion", async () => {
    const state = setup(); const list = select(state); const old = deferred<ReturnType<typeof page>>(); const latest = deferred<ReturnType<typeof page>>();
    list.request.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    const first = list.load(); const signal = list.request.mock.calls[0]![1].signal as AbortSignal;
    list.filter.page = 2; const second = list.load(); old.resolve(page(1, 100)); await first;
    expect(signal.aborted).toBe(true); expect(list.loading.value).toBe(true); expect(list.rows.value).toEqual([]);
    latest.resolve(page(2, 30)); await second; expect(list.rows.value[0]!.id).toBe(2); expect(list.total.value).toBe(30);
    expect(list.loading.value).toBe(false); expect(list.stale.value).toBe(false); expect(list.request.mock.calls[1]![0].page).toBe(2);
  });

  it("ignores an older error after a newer successful refresh", async () => {
    const list = select(setup()); const old = deferred<ReturnType<typeof page>>(); list.request.mockReturnValueOnce(old.promise);
    const first = list.load(); await list.load(); old.reject(new Error("old offline")); await first;
    expect(list.error.value).toBe(""); expect(list.rows.value[0]!.id).toBe(1); expect(mocks.error).not.toHaveBeenCalled();
  });

  it.each(["page", "pageSize", "status", "taskId"] as const)("invalidates a pending query immediately when %s changes", async field => {
    const list = select(setup()); const pending = deferred<ReturnType<typeof page>>(); list.request.mockReturnValueOnce(pending.promise);
    const call = list.load(); const signal = list.request.mock.calls[0]![1].signal as AbortSignal;
    if (field === "status") list.filter.status = "SUCCESS"; else list.filter[field] = 2;
    expect(signal.aborted).toBe(true); expect(list.loading.value).toBe(false); pending.resolve(page()); await call;
    expect(list.rows.value).toEqual([]); expect(list.total.value).toBe(0); expect(list.stale.value).toBe(true);
  });

  it("keeps the previous same-filter query labelled stale after a current failure", async () => {
    const list = select(setup()); await list.load(); list.request.mockRejectedValueOnce(new Error("current offline")); await list.load();
    expect(list.rows.value).toEqual(page().items); expect(list.total.value).toBe(25); expect(list.stale.value).toBe(true);
    expect(list.error.value).toBe("current offline"); await list.load(); expect(list.error.value).toBe(""); expect(list.stale.value).toBe(false);
  });

  it("aborts on disposal and suppresses a late error without starting new requests", async () => {
    const state = setup(); const list = select(state); const pending = deferred<ReturnType<typeof page>>(); list.request.mockReturnValueOnce(pending.promise);
    const call = list.load(); const signal = list.request.mock.calls[0]![1].signal as AbortSignal;
    state.scope.stop(); pending.reject(new Error("late offline")); await call; await list.load();
    expect(signal.aborted).toBe(true); expect(list.error.value).toBe(""); expect(list.request).toHaveBeenCalledTimes(1);
  });
});

describe("independent notification records", () => {
  it("keeps event and delivery read controllers and failures independent", async () => {
    const state = setup(); const event = deferred<ReturnType<typeof page>>(); const delivery = deferred<ReturnType<typeof page>>();
    mocks.events.mockReturnValueOnce(event.promise); mocks.deliveries.mockReturnValueOnce(delivery.promise);
    const first = state.loadNotificationEvents(); const second = state.loadNotificationDeliveries();
    event.reject(new Error("events offline")); await first; expect(state.eventsLoading.value).toBe(false); expect(state.deliveriesLoading.value).toBe(true);
    delivery.resolve(page(2)); await second; expect(state.deliveriesError.value).toBe(""); expect(state.notificationDeliveries.value[0]!.id).toBe(2);
  });

  it.each(["permission", "tenant"])("clears both lists and ignores old results after %s changes and switches back", async kind => {
    const state = setup(); await state.refreshNotificationData(); const pending = deferred<ReturnType<typeof page>>();
    mocks.events.mockReturnValueOnce(pending.promise); const read = state.loadNotificationEvents();
    if (kind === "permission") { state.canManage.value = false; state.canManage.value = true; }
    else { setActiveTenant("other"); clearActiveTenant(); }
    pending.resolve(page()); await read; expect(state.notificationEvents.value).toEqual([]); expect(state.notificationDeliveries.value).toEqual([]);
  });

  it("only retries freshly verified rows and retains enqueue acknowledgement after a following read failure", async () => {
    const state = setup(); await state.retryEvent(1); expect(mocks.retry).not.toHaveBeenCalled(); await state.loadNotificationEvents();
    mocks.events.mockRejectedValueOnce(new Error("refresh offline")); await state.retryEvent(1);
    expect(mocks.success).toHaveBeenCalledWith("通知事件已重新入队"); expect(mocks.error).not.toHaveBeenCalled();
    expect(state.eventsError.value).toBe("refresh offline"); expect(state.canRetryEvent(1)).toBe(false);
  });

  it("handles a synchronous binding refresh failure without misreporting the enqueue", async () => {
    const state = setup(); await state.loadNotificationEvents(); state.bindings.mockImplementationOnce(() => { throw new Error("bindings offline"); });
    await state.retryEvent(1); expect(mocks.success).toHaveBeenCalledWith("通知事件已重新入队"); expect(mocks.error).not.toHaveBeenCalled();
    expect(mocks.warning).toHaveBeenCalledWith("渠道绑定刷新失败：bindings offline");
  });

  it("does not refresh a different tenant after a pending retry finishes", async () => {
    const state = setup(); await state.loadNotificationEvents(); const pending = deferred<void>(); mocks.retry.mockReturnValueOnce(pending.promise);
    const call = state.retryEvent(1); setActiveTenant("other"); pending.resolve(); await call;
    expect(mocks.success).not.toHaveBeenCalled(); expect(state.bindings).not.toHaveBeenCalled(); expect(mocks.events).toHaveBeenCalledTimes(1);
  });
});
