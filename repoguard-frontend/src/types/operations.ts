import type { FrontendPerformanceReport as FrontendPerformanceObservationReport } from "../observability/frontendPerformanceBuffer";

export interface CacheStatsItem {
  name: string;
  estimatedSize: number;
  requestCount: number;
  hitCount: number;
  missCount: number;
  hitRate: number;
  evictionCount: number;
}

export interface CacheStats {
  caches: CacheStatsItem[];
}

export interface DataRetentionCleanupRequest {
  retentionDays?: number;
  maxTasks?: number;
  execute?: boolean;
  backupReference?: string;
  confirmText?: string;
}

export interface DataRetentionCleanupResponse {
  executed: boolean;
  cleanupBatchId: number;
  retentionDays: number;
  maxTasks: number;
  backupReference?: string;
  cutoffTime: string;
  candidateTasks: number;
  selectedTasks: number;
  deletedBatchItems: number;
  deletedPublications: number;
  deletedBatches: number;
  deletedChangedFiles: number;
  deletedTimelines: number;
  deletedFindings: number;
  deletedTasks: number;
}

export interface DataRetentionCleanupAudit {
  id: number;
  mode: string;
  status: string;
  retentionDays?: number;
  maxTasks?: number;
  backupReference?: string;
  cutoffTime?: string;
  candidateTasks?: number;
  selectedTasks?: number;
  deletedBatchItems?: number;
  deletedPublications?: number;
  deletedBatches?: number;
  deletedChangedFiles?: number;
  deletedTimelines?: number;
  deletedFindings?: number;
  deletedTasks?: number;
  failureReason?: string;
  failureMessage?: string;
  createdAt?: string;
  completedAt?: string;
  updatedAt?: string;
}

export interface UserRoleUpdateRequest {
  role: "ADMIN" | "VIEWER";
}

export interface UserStatusUpdateRequest {
  status: "ACTIVE" | "DISABLED";
}

export type FrontendPerformanceReport = FrontendPerformanceObservationReport;
