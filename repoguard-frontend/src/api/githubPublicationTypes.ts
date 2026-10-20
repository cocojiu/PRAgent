export type GithubReferenceKind = "issue_comment" | "review_comment" | "review";
export type PendingGithubPublication = {
  id: number;
  findingId?: number | null;
  targetType: string;
  status: string;
  version: string;
  verifiable: boolean;
  message?: string | null;
  url?: string | null;
};
export type GithubPublicationVerifyRequest = { version: string; kind: GithubReferenceKind; remoteId: number };
export type GithubPublicationCloseRequest = { version: string; reason: string; confirmNoRetry: boolean };
export const isPendingGithubPublication = (value: unknown): value is PendingGithubPublication => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const item = value as Record<string, unknown>;
  return Number.isSafeInteger(item.id) && Number(item.id) > 0 && typeof item.targetType === "string"
    && typeof item.status === "string" && typeof item.version === "string" && /^[0-9a-f]{64}$/.test(item.version)
    && typeof item.verifiable === "boolean" && (item.message == null || typeof item.message === "string")
    && (item.url == null || typeof item.url === "string");
};
export const isPendingGithubPublications = (value: unknown): value is PendingGithubPublication[] =>
  Array.isArray(value) && value.length <= 50 && value.every(isPendingGithubPublication);
