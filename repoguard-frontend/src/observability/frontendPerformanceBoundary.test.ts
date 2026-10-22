import { beforeEach, describe, expect, it, vi } from "vitest";
import * as buffer from "./frontendPerformanceBuffer";

let route = "overview";
const api = { operation: "fetchReviewFindings", method: "GET", result: "success" as const,
  traceId: "old-trace", startedAtMs: 10, durationMs: 10 };
const render = { operation: "fetchReviewFindings", region: "detail", startedAtMs: 30, durationMs: 5 };

describe("performance observation boundaries", () => {
  beforeEach(() => {
    route = "overview";
    buffer.configureFrontendPerformanceBuffer({ shouldRecord: () => true, scheduleFlush: vi.fn(), route: () => route });
    buffer.resetFrontendPerformanceBoundary();
  });

  it("groups records by their original navigation instead of the flush route", () => {
    const original = buffer.capturePerformanceContext();
    route = "tasks";
    buffer.advanceFrontendPerformanceNavigation();
    buffer.observeFrontendApiRequest(api, original);
    buffer.observeFrontendApiRequest({ ...api, traceId: "new-trace" });
    expect(buffer.drainFrontendPerformanceReport("tasks")).toMatchObject({
      route: "overview", apiRequests: [{ traceId: "old-trace" }]
    });
    expect(buffer.drainFrontendPerformanceReport()).toMatchObject({
      route: "tasks", apiRequests: [{ traceId: "new-trace" }]
    });
  });

  it("drops old tenant/session completions and clears pending data and associations", () => {
    const old = buffer.capturePerformanceContext();
    buffer.observeFrontendApiRequest(api);
    buffer.resetFrontendPerformanceBoundary();
    buffer.observeFrontendApiRequest(api, old);
    buffer.observeFrontendLongTask(render, old);
    expect(buffer.hasFrontendPerformanceObservations()).toBe(false);
    buffer.observeFrontendLongTask(render);
    const report = buffer.drainFrontendPerformanceReport();
    expect(report.apiRequests).toEqual([]);
    expect(report.longTasks[0]?.apiTraceId).toBeUndefined();
  });

  it("does not correlate the same operation across navigations", () => {
    buffer.observeFrontendApiRequest(api);
    buffer.advanceFrontendPerformanceNavigation();
    buffer.observeFrontendLongTask(render);
    buffer.drainFrontendPerformanceReport();
    expect(buffer.drainFrontendPerformanceReport().longTasks[0]?.apiTraceId).toBeUndefined();
  });

  it("keeps a bounded buffer and counts discarded observations", () => {
    const before = buffer.getFrontendPerformanceDeliveryStats().dropped;
    for (let index = 0; index < 80; index++) buffer.observeFrontendApiRequest(api);
    expect(buffer.drainFrontendPerformanceReport().apiRequests).toHaveLength(50);
    expect(buffer.getFrontendPerformanceDeliveryStats().dropped - before).toBe(30);
  });

  it("invokes the boundary cancellation hook", () => {
    const abort = vi.fn();
    buffer.configureFrontendPerformanceBuffer({ shouldRecord: () => true, scheduleFlush: vi.fn(), onBoundaryReset: abort });
    buffer.resetFrontendPerformanceBoundary();
    expect(abort).toHaveBeenCalledOnce();
  });
});
