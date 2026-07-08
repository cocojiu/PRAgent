import { describe, expect, it, vi } from "vitest";
import {
  boundedDetailItems,
  hiddenDetailItemCount,
  isDetailTextTruncated,
  truncateDetailText
} from "./reviewDetailRenderBudget";

describe("reviewDetailRenderBudget", () => {
  it("bounds large detail pages before rendering", () => {
    const items = Array.from({ length: 35 }, (_, index) => index + 1);

    expect(boundedDetailItems(items)).toHaveLength(20);
    expect(hiddenDetailItemCount(items)).toBe(15);
  });

  it("keeps long text behind a short preview", () => {
    const text = "x".repeat(420);

    expect(isDetailTextTruncated(text, 100)).toBe(true);
    expect(truncateDetailText(text, 100)).toHaveLength(103);
    expect(truncateDetailText(" short ", 100)).toBe("short");
  });

  it("observes bounded render regions with list counts", async () => {
    vi.resetModules();
    const observer = vi.fn();
    vi.doMock("@/observability/frontendPerformanceBuffer", () => ({
      observeFrontendLongTask: observer
    }));
    const budget = await import("./reviewDetailRenderBudget");

    await budget.observeDetailRegionRender({
      region: "review-detail.comment-preview",
      operation: "fetchGithubCommentPreview",
      itemCount: 20,
      totalCount: 260,
      startedAtMs: 10
    });

    expect(observer).toHaveBeenCalledWith(expect.objectContaining({
      region: "review-detail.comment-preview",
      operation: "fetchGithubCommentPreview",
      itemCount: 20,
      totalCount: 260
    }));
  });
});
