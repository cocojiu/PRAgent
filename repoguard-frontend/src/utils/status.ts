import type { ReviewStatus } from "@/types";

const statusLabelMap: Record<ReviewStatus, string> = {
  completed: "已完成",
  reviewing: "审查中",
  failed: "失败",
  queued: "已入队"
};

const statusClassMap: Record<ReviewStatus, string> = {
  completed: "success",
  reviewing: "processing",
  failed: "danger",
  queued: "processing"
};

export const statusText = (status: ReviewStatus) => statusLabelMap[status] ?? status;
export const statusClass = (status: ReviewStatus) => statusClassMap[status] ?? "processing";
