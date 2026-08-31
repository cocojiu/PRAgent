export type RepoGuardEdition = "personal" | "enterprise-experimental";

const configuredEdition = import.meta.env.VITE_REPOGUARD_EDITION?.trim().toLowerCase();

/** Unknown or missing frontend values fail closed to the personal surface. */
export const APP_EDITION: RepoGuardEdition = configuredEdition === "enterprise-experimental"
  ? "enterprise-experimental"
  : "personal";

export const enterpriseEditionEnabled = APP_EDITION === "enterprise-experimental";
