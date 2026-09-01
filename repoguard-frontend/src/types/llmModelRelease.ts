export interface LlmModelReleaseRequest {
  releaseKey: string;
  provider: string;
  modelName: string;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  datasetId: string;
  datasetVersion: string;
  datasetFingerprint: string;
  trafficPercent: number;
  qualityGatePassed: boolean;
  precisionRate: number;
  recallRate: number;
  anchorRate: number;
  duplicateRate: number;
  parseFailureRate: number;
  p95LatencyMs: number;
  averageCost: number;
  totalTokens: number;
  blockers?: string[];
}

export interface LlmModelRollbackRequest {
  reason: string;
}

export interface LlmModelRelease {
  id: number;
  releaseKey: string;
  provider: string;
  modelName: string;
  promptVersion: string;
  contextVersion: string;
  schemaVersion: string;
  datasetId: string;
  datasetVersion: string;
  datasetFingerprint: string;
  state: "SHADOW" | "CANARY" | "ACTIVE" | "ROLLED_BACK" | string;
  trafficPercent: number;
  qualityGatePassed: boolean;
  precisionRate: number;
  recallRate: number;
  anchorRate: number;
  duplicateRate: number;
  parseFailureRate: number;
  p95LatencyMs: number;
  averageCost: number;
  totalTokens: number;
  blockers: string[];
  rollbackReason?: string;
  createdBy: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface LlmModelBudget {
  month: string;
  tokenBudget: number;
  tokenUsed: number;
  tokenRemaining: number;
  costBudget: number;
  costUsed: number;
  costRemaining: number;
  exhausted: boolean;
}

export interface LlmModelReleaseCenter {
  configuredProvider: string;
  configuredModel: string;
  activeRelease?: LlmModelRelease;
  canaryRelease?: LlmModelRelease;
  releases: LlmModelRelease[];
  modelComparison: Array<{
    model: string;
    taskCount: number;
    averageDuration: string;
    averageTokens: string;
    averageCost: string;
    parseSuccessRate: string;
    fallbackRate: string;
    partialFallbackRate: string;
    validRate: string;
    falsePositiveRate: string;
  }>;
  monthlyBudget: LlmModelBudget;
  recommendedAction: string;
}
