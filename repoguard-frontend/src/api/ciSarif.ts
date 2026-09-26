import { apiRequest, type ApiRequestOptions } from "@/api/contracts";

export interface CiSarifUpload {
  batchId: number;
  toolName: string;
  toolVersion?: string;
  scanRunId: string;
  status: string;
  imported: number;
  skipped: number;
  completedAt: string;
}

export interface CiSarifSetup {
  taskId: number;
  attemptId?: number;
  organization: string;
  repository: string;
  prNumber?: number;
  commitSha?: string;
  credentialTtlSeconds: number;
  maxUploadBytes: number;
  maxSarifBytes: number;
  sarifVersion: string;
  recentUploads: CiSarifUpload[];
}

export interface CiSarifCredential {
  credential: string;
  expiresAt: number;
  taskId: number;
  attemptId: number;
  organization: string;
  repository: string;
  prNumber?: number;
  commitSha: string;
}

export const fetchCiSarifSetup = (taskId: number, options?: ApiRequestOptions) =>
  apiRequest("fetchCiSarifSetup", { taskId }, options);

export const issueCiSarifCredential = (taskId: number, attemptId: number) =>
  apiRequest("issueCiSarifCredential", { taskId, attemptId });
