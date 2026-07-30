import type { ReviewStatus } from "@/types";

const statusLabelMap: Record<ReviewStatus, string> = {
  completed: "已完成",
  reviewing: "审查中",
  failed: "失败",
  superseded: "已过期",
  queued: "已入队",
  fallback: "规则兜底",
  pending: "等待中",
  pending_human_review: "待人工审查",
  approved: "人工通过",
  changes_requested: "要求修改",
  rejected: "已拒绝"
};

const statusClassMap: Record<ReviewStatus, string> = {
  completed: "success",
  reviewing: "processing",
  failed: "danger",
  superseded: "warning",
  queued: "processing",
  fallback: "warning",
  pending: "processing",
  pending_human_review: "warning",
  approved: "success",
  changes_requested: "warning",
  rejected: "danger"
};

export const statusText = (status: ReviewStatus) => statusLabelMap[status] ?? status;
export const statusClass = (status: ReviewStatus) => statusClassMap[status] ?? "processing";
