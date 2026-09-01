export type EnterpriseTenantStatus = "ACTIVE" | "SUSPENDED";

export type EnterpriseRole =
  | "ADMIN"
  | "VIEWER"
  | "PLATFORM_ADMIN"
  | "TENANT_ADMIN"
  | "RULE_ADMIN"
  | "REVIEWER"
  | "READ_ONLY";

export interface EnterpriseTenant {
  tenantId: number;
  tenantKey: string;
  displayName: string;
  status: EnterpriseTenantStatus;
  statusVersion: number;
  statusReason?: string;
  statusChangedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface EnterpriseTenantCreateRequest {
  tenantKey: string;
  displayName: string;
  initialAdminUserId: number;
}

export interface EnterpriseTenantStatusRequest {
  expectedStatus: EnterpriseTenantStatus;
  targetStatus: EnterpriseTenantStatus;
  expectedVersion: number;
  reason: string;
}

export interface EnterpriseTenantMembershipRequest {
  userId: number;
  role: Exclude<EnterpriseRole, "PLATFORM_ADMIN">;
  defaultTenant: boolean;
}

export interface EnterpriseTenantRepositoryRequest {
  organization: string;
  repository: string;
  githubInstallationId: number;
}

export interface EnterpriseIdentityBindingRequest {
  userId: number;
  issuer: string;
  subject: string;
}

export interface EnterpriseTenantQuota {
  tenantId: number;
  tenantKey: string;
  quotaVersion: number;
  maxDailyReviews: number;
  usedReviews: number;
  usageDate?: string;
  updatedAt?: string;
}

export interface EnterpriseTenantQuotaRequest {
  expectedVersion: number;
  maxDailyReviews: number;
}
