import type { ReviewTask } from "@/types";

export const reviewTaskSourceText = (source?: string) => {
  const labels: Record<string, string> = {
    manual_input: "手动输入",
    github_pr_picker: "PR 选择",
    github_webhook: "GitHub 自动触发",
    existing_reused: "复用已有"
  };
  return source ? labels[source] ?? source : "手动输入";
};

export const reviewTaskSourceClass = (source?: string) => {
  const classes: Record<string, string> = {
    manual_input: "manual",
    github_pr_picker: "github",
    github_webhook: "github",
    existing_reused: "reused"
  };
  return source ? classes[source] ?? "manual" : "manual";
};

export const canRetryReviewTask = (task: ReviewTask) =>
  task.status === "failed" || task.status === "superseded";

export const reviewTaskRetryTooltip = (task: ReviewTask) => {
  if (!canRetryReviewTask(task)) {
    return "仅失败或已过期任务支持重试";
  }
  if (task.status === "superseded") {
    return task.failureSuggestion || "读取 PR 最新提交并重新审查";
  }
  return task.failureSuggestion || "重新入队执行审查";
};

export const reviewTaskRetryText = (task: ReviewTask) =>
  task.status === "superseded" ? "按最新提交重评" : "重试";
