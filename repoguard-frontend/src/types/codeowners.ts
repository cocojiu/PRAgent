export interface CodeownersCandidate {
  userId: number;
  username: string;
  coveredFiles: number;
  findingCount: number;
  riskScore: number;
  externalIdentities: string[];
  paths: string[];
}
export interface CodeownersPath {
  path: string;
  pattern?: string | null;
  line: number;
  owners: string[];
  changedFiles: string[];
}
export interface CodeownersRecommendations {
  status: string;
  taskId: number;
  attemptId?: number | null;
  headSha?: string | null;
  baseSha?: string | null;
  sourcePath?: string | null;
  candidates: CodeownersCandidate[];
  basis: CodeownersPath[];
  uncoveredPaths: string[];
  unmappedIdentities: string[];
}

export interface CodeownersAcceptanceRequest { userId: number; attemptId: number; headSha: string; baseSha: string; }
export interface CodeownersAcceptance { taskId: number; attemptId: number; headSha: string; assignee: string; }
