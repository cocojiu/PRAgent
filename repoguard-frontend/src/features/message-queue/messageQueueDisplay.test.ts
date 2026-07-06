import { describe, expect, it } from "vitest";
import {
  canRequeueMessageQueueStatus,
  messageQueueMetricLabel,
  messageQueueMetricNote,
  messageQueueRequeueTooltip,
  messageQueueStatusClass,
  messageQueueStatusText
} from "./messageQueueDisplay";

describe("message queue display mappers", () => {
  it("shows execution timeout and requeue pending task states", () => {
    expect(messageQueueStatusText("EXECUTION_TIMEOUT")).toBe("执行超时");
    expect(messageQueueStatusClass("EXECUTION_TIMEOUT")).toBe("danger");
    expect(messageQueueStatusText("REQUEUE_PENDING")).toBe("重入队中");
    expect(messageQueueStatusClass("REQUEUE_PENDING")).toBe("processing");
  });

  it("aligns manual requeue availability with backend state machine", () => {
    expect(canRequeueMessageQueueStatus("PUBLISH_FAILED")).toBe(true);
    expect(canRequeueMessageQueueStatus("EXECUTION_TIMEOUT")).toBe(true);
    expect(canRequeueMessageQueueStatus("REQUEUE_PENDING")).toBe(false);
    expect(canRequeueMessageQueueStatus("RETRY_EXHAUSTED")).toBe(false);
  });

  it("translates recovery metrics and action tooltips", () => {
    expect(messageQueueMetricLabel("Execution timeout")).toBe("执行超时");
    expect(messageQueueMetricLabel("Requeue pending")).toBe("重入队中");
    expect(messageQueueMetricNote("Review lease expired")).toBe("执行租约已过期");
    expect(messageQueueMetricNote("Execution recovery publishing")).toBe("执行恢复发布中");
    expect(messageQueueRequeueTooltip("EXECUTION_TIMEOUT")).toContain("执行超时");
    expect(messageQueueRequeueTooltip("REQUEUE_PENDING")).toContain("恢复发布中");
  });
});
