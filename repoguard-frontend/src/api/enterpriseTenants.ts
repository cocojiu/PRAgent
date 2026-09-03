import { apiRequest } from "@/api/contracts";
import type {
  EnterpriseIdentityBindingRequest,
  EnterpriseTenant,
  EnterpriseTenantCreateRequest,
  EnterpriseTenantMembershipRequest,
  EnterpriseTenantQuota,
  EnterpriseTenantQuotaRequest,
  EnterpriseTenantRepositoryRequest,
  EnterpriseTenantStatusRequest,
  EnterpriseTenantStatus
} from "@/types";

export type EnterpriseTenantPageQuery = {
  page?: number;
  pageSize?: number;
  status?: EnterpriseTenantStatus | "";
};

export const fetchEnterpriseTenants = (query: EnterpriseTenantPageQuery = {}) =>
  apiRequest("fetchEnterpriseTenants", query);

export const fetchEnterpriseTenant = (tenantKey: string) =>
  apiRequest("fetchEnterpriseTenant", { tenantKey });

export const createEnterpriseTenant = (payload: EnterpriseTenantCreateRequest) =>
  apiRequest("createEnterpriseTenant", payload);

export const updateEnterpriseTenantStatus = (tenantKey: string, payload: EnterpriseTenantStatusRequest) =>
  apiRequest("updateEnterpriseTenantStatus", { tenantKey, payload });

export const bindEnterpriseTenantMembership = (
  tenantKey: string,
  payload: EnterpriseTenantMembershipRequest
) => apiRequest("bindEnterpriseTenantMembership", { tenantKey, payload });

export const bindEnterpriseTenantRepository = (
  tenantKey: string,
  payload: EnterpriseTenantRepositoryRequest
) => apiRequest("bindEnterpriseTenantRepository", { tenantKey, payload });

export const bindEnterpriseTenantIdentity = (
  tenantKey: string,
  payload: EnterpriseIdentityBindingRequest
) => apiRequest("bindEnterpriseTenantIdentity", { tenantKey, payload });

export const fetchEnterpriseTenantQuota = (tenantKey: string): Promise<EnterpriseTenantQuota> =>
  apiRequest("fetchEnterpriseTenantQuota", { tenantKey });

export const updateEnterpriseTenantQuota = (
  tenantKey: string,
  payload: EnterpriseTenantQuotaRequest
) => apiRequest("updateEnterpriseTenantQuota", { tenantKey, payload });

export type { EnterpriseTenant };
