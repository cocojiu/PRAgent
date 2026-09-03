export type RepositoryPolicyRuleOverride = {
  enabled?: boolean;
  severity?: string;
  enforcementMode?: string;
};

export type RepositoryPolicyLlmOverride = {
  enabled?: boolean;
  tokenBudget?: number;
  costBudget?: number;
};

export type RepositoryPolicyPublicationOverride = {
  commentMode?: string;
  checkMode?: string;
};

export type RepositoryPolicySuppressionReference = {
  ruleId: string;
  fileGlob?: string;
  symbol?: string;
  reason: string;
  expiresAt: string;
};

export type RepositoryPolicyDocument = {
  schemaVersion: number;
  includePatterns: string[];
  excludePatterns: string[];
  rules: Record<string, RepositoryPolicyRuleOverride>;
  llm?: RepositoryPolicyLlmOverride;
  publication?: RepositoryPolicyPublicationOverride;
  suppressions: RepositoryPolicySuppressionReference[];
};

export type RepositoryPolicyRuleDecision = {
  ruleId: string;
  adminEnabled: boolean;
  baseEnabled?: boolean;
  effectiveEnabled: boolean;
  adminSeverity: string;
  baseSeverity?: string;
  effectiveSeverity: string;
  adminEnforcement: string;
  baseEnforcement?: string;
  effectiveEnforcement: string;
  conflict?: string;
};

export type RepositoryPolicyPreviewResponse = {
  basePolicy: RepositoryPolicyDocument;
  headPolicy: RepositoryPolicyDocument;
  rules: Record<string, RepositoryPolicyRuleDecision>;
  effectiveLlmEnabled?: boolean;
  effectiveTokenBudget?: number;
  effectiveCostBudget?: number;
  commentMode: string;
  checkMode: string;
  warnings: string[];
};

export type RepositorySuppressionRequest = {
  organization: string;
  repository: string;
  ruleId: string;
  fileGlob?: string;
  symbol?: string;
  reason: string;
  expiresAt: string;
};

export type RepositorySuppressionResponse = {
  id: number;
  organization: string;
  repository: string;
  ruleId: string;
  fileGlob?: string;
  symbol?: string;
  reason: string;
  status: string;
  operator: string;
  expiresAt: string;
  previewHitCount: number;
  hitCount: number;
  createdAt: string;
  updatedAt: string;
};
