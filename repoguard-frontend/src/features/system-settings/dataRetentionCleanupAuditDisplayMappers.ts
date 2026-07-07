import type { DataRetentionCleanupAudit } from "@/types";

export type DataRetentionCleanupAuditTone = "success" | "warning" | "danger" | "info" | "processing" | "pending";

const normalized = (value?: string) => value?.trim().toUpperCase() ?? "";

export const cleanupAuditModeText = (mode?: string) => {
  switch (normalized(mode)) {
    case "EXECUTE":
      return "执行";
    case "DRY_RUN":
      return "预检";
    default:
      return mode || "-";
  }
};

export const cleanupAuditStatusText = (status?: string) => {
  switch (normalized(status)) {
    case "COMPLETED":
      return "已完成";
    case "FAILED":
      return "失败";
    case "STARTED":
      return "执行中";
    default:
      return status || "-";
  }
};

export const cleanupAuditStatusClass = (status?: string): DataRetentionCleanupAuditTone => {
  switch (normalized(status)) {
    case "COMPLETED":
      return "success";
    case "FAILED":
      return "danger";
    case "STARTED":
      return "processing";
    default:
      return "pending";
  }
};

export const cleanupAuditBackupReferenceText = (audit: DataRetentionCleanupAudit) =>
  audit.backupReference?.trim() || "-";

export const cleanupAuditTaskSummaryText = (audit: DataRetentionCleanupAudit) => {
  const candidateTasks = audit.candidateTasks ?? 0;
  const selectedTasks = audit.selectedTasks ?? 0;
  const deletedTasks = audit.deletedTasks ?? 0;
  return `候选 ${candidateTasks} / 选中 ${selectedTasks} / 删除 ${deletedTasks}`;
};

export const cleanupAuditDeletedChildrenText = (audit: DataRetentionCleanupAudit) => {
  const changedFiles = audit.deletedChangedFiles ?? 0;
  const findings = audit.deletedFindings ?? 0;
  const timelines = audit.deletedTimelines ?? 0;
  const publications = (audit.deletedBatchItems ?? 0)
    + (audit.deletedPublications ?? 0)
    + (audit.deletedBatches ?? 0);
  return `文件 ${changedFiles} / 问题 ${findings} / 时间线 ${timelines} / 回写 ${publications}`;
};

export const cleanupAuditFailureText = (audit: DataRetentionCleanupAudit) => {
  const reason = audit.failureReason?.trim();
  const message = audit.failureMessage?.trim();
  if (reason && message) {
    return `${reason}: ${message}`;
  }
  return reason || message || "-";
};
