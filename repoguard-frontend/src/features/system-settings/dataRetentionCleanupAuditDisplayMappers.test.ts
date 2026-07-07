import { describe, expect, it } from "vitest";
import {
  cleanupAuditBackupReferenceText,
  cleanupAuditDeletedChildrenText,
  cleanupAuditFailureText,
  cleanupAuditModeText,
  cleanupAuditStatusClass,
  cleanupAuditStatusText,
  cleanupAuditTaskSummaryText
} from "./dataRetentionCleanupAuditDisplayMappers";
import type { DataRetentionCleanupAudit } from "@/types";

describe("dataRetentionCleanupAuditDisplayMappers", () => {
  it("maps cleanup modes and statuses into stable admin labels", () => {
    expect(cleanupAuditModeText("dry_run")).toBe("预检");
    expect(cleanupAuditModeText("execute")).toBe("执行");
    expect(cleanupAuditStatusText("STARTED")).toBe("执行中");
    expect(cleanupAuditStatusText("COMPLETED")).toBe("已完成");
    expect(cleanupAuditStatusText("FAILED")).toBe("失败");
    expect(cleanupAuditStatusClass("STARTED")).toBe("processing");
    expect(cleanupAuditStatusClass("COMPLETED")).toBe("success");
    expect(cleanupAuditStatusClass("FAILED")).toBe("danger");
  });

  it("summarizes backup, task, child deletion, and failure fields", () => {
    const audit: DataRetentionCleanupAudit = {
      id: 77,
      mode: "execute",
      status: "FAILED",
      retentionDays: 90,
      maxTasks: 500,
      backupReference: " backup://mysql/prod/2026-07-07T22:00:00 ",
      candidateTasks: 12,
      selectedTasks: 3,
      deletedTasks: 2,
      deletedChangedFiles: 5,
      deletedFindings: 8,
      deletedTimelines: 13,
      deletedBatchItems: 1,
      deletedPublications: 2,
      deletedBatches: 3,
      failureReason: "database_error",
      failureMessage: "connection reset"
    };

    expect(cleanupAuditBackupReferenceText(audit)).toBe("backup://mysql/prod/2026-07-07T22:00:00");
    expect(cleanupAuditTaskSummaryText(audit)).toBe("候选 12 / 选中 3 / 删除 2");
    expect(cleanupAuditDeletedChildrenText(audit)).toBe("文件 5 / 问题 8 / 时间线 13 / 回写 6");
    expect(cleanupAuditFailureText(audit)).toBe("database_error: connection reset");
    expect(cleanupAuditFailureText({ ...audit, failureReason: "", failureMessage: "" })).toBe("-");
  });
});
