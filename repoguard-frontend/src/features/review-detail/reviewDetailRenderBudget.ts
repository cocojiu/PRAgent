import { nextTick } from "vue";
import { observeFrontendLongTask } from "@/observability/frontendPerformanceBuffer";

export const DETAIL_RENDER_ITEM_LIMIT = 20;
export const DETAIL_TEXT_PREVIEW_CHARS = 360;
export const COMMENT_BODY_PREVIEW_CHARS = 1200;

type RenderRegion = "review-detail.findings" | "review-detail.changed-files" | "review-detail.comment-preview";

type ObserveRenderBudgetInput = {
  region: RenderRegion;
  operation: string;
  itemCount: number;
  totalCount: number;
  startedAtMs: number;
};

export const boundedDetailItems = <T>(items: T[], limit = DETAIL_RENDER_ITEM_LIMIT) =>
  items.slice(0, Math.max(1, limit));

export const hiddenDetailItemCount = <T>(items: T[], limit = DETAIL_RENDER_ITEM_LIMIT) =>
  Math.max(0, items.length - Math.max(1, limit));

export const truncateDetailText = (value: string | undefined, maxLength = DETAIL_TEXT_PREVIEW_CHARS) => {
  const normalized = value?.trim() ?? "";
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return `${normalized.slice(0, Math.max(0, maxLength)).trimEnd()}...`;
};

export const isDetailTextTruncated = (value: string | undefined, maxLength = DETAIL_TEXT_PREVIEW_CHARS) =>
  (value?.trim().length ?? 0) > maxLength;

export const observeDetailRegionRender = async (input: ObserveRenderBudgetInput) => {
  await nextTick();
  observeFrontendLongTask({
    startedAtMs: input.startedAtMs,
    durationMs: now() - input.startedAtMs,
    region: input.region,
    operation: input.operation,
    itemCount: input.itemCount,
    totalCount: input.totalCount
  });
};

const now = () => (typeof performance === "undefined" ? Date.now() : performance.now());
