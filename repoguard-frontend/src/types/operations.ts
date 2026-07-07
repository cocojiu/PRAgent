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
