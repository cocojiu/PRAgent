export interface BackupStatus {
  status: string;
  checkedAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  ageSeconds?: number | null;
  maxAgeHours: number;
  retained?: number | null;
  archiveBytes?: number | null;
  reasonCode?: string | null;
  restoreVerified: boolean;
  archiveIntegrityChecked: boolean;
  processLivenessChecked: boolean;
  timerEnabledChecked: boolean;
}
