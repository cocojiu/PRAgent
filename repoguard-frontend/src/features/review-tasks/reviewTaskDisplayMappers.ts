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

export const canRetryReviewTask = (task: ReviewTask) => task.status === "failed";

export const reviewTaskRetryTooltip = (task: ReviewTask) => {
  if (!canRetryReviewTask(task)) {
    return "仅失败任务支持重试";
  }
  return task.failureSuggestion || "重新入队执行审查";
};
