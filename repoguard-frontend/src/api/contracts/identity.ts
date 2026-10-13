import type { ApiEndpointMap } from "@/api/endpoint";
import type {
  ApiOperation
} from "@/api/endpoint";
import {
  generatedEndpoint
} from "@/api/generatedEndpoint";
import type {
  AuthResponse,
  CurrentUser,
  LoginRequest,
  PasswordChangeRequest,
  RefreshTokenResetRequest,
  RegisterRequest
} from "@/api/auth";
import type {
  ManagedUser,
  UserCreateRequest,
  UserOperationAudit,
  UserRole,
  UserStatus
} from "@/api/users";
import {
  isAuthResponse,
  isCurrentUser
} from "@/api/responseValidation";
import type {
  EnterpriseIdentityBindingRequest,
  EnterpriseTenant,
  EnterpriseTenantCreateRequest,
  EnterpriseTenantMembershipRequest,
  EnterpriseTenantQuota,
  EnterpriseTenantQuotaRequest,
  EnterpriseTenantRepositoryRequest,
  EnterpriseTenantStatusRequest,
  PageResponse
} from "@/types";

type UserRoleInput = {
  id: number;
  role: UserRole;
};

type UserStatusInput = {
  id: number;
  status: UserStatus;
};

type UserPageInput = {
  page?: number;
  pageSize?: number;
  role?: UserRole | "";
  status?: UserStatus | "";
  keyword?: string;
};

type EnterpriseTenantPageInput = {
  page?: number;
  pageSize?: number;
  status?: string;
};

type EnterpriseTenantCreateInput = EnterpriseTenantCreateRequest;

type EnterpriseTenantStatusInput = { tenantKey: string; payload: EnterpriseTenantStatusRequest };

type EnterpriseTenantMembershipInput = {
  tenantKey: string;
  payload: EnterpriseTenantMembershipRequest;
};

type EnterpriseTenantRepositoryInput = {
  tenantKey: string;
  payload: EnterpriseTenantRepositoryRequest;
};

type EnterpriseTenantIdentityInput = {
  tenantKey: string;
  payload: EnterpriseIdentityBindingRequest;
};

type EnterpriseTenantQuotaInput = { tenantKey: string; payload: EnterpriseTenantQuotaRequest };

export type IdentityApiContract = {
  login: ApiOperation<LoginRequest, AuthResponse>;
  register: ApiOperation<RegisterRequest, AuthResponse>;
  getCurrentUser: ApiOperation<undefined, CurrentUser>;
  changePassword: ApiOperation<PasswordChangeRequest, void>;
  resetRefreshToken: ApiOperation<RefreshTokenResetRequest, AuthResponse>;
  logout: ApiOperation<undefined, void>;
  fetchUsers: ApiOperation<UserPageInput, PageResponse<ManagedUser>>;
  fetchUserOperationAudits: ApiOperation<UserPageInput, PageResponse<UserOperationAudit>>;
  createUser: ApiOperation<UserCreateRequest, ManagedUser>;
  updateUserRole: ApiOperation<UserRoleInput, ManagedUser>;
  updateUserStatus: ApiOperation<UserStatusInput, ManagedUser>;
  fetchEnterpriseTenants: ApiOperation<EnterpriseTenantPageInput, PageResponse<EnterpriseTenant>>;
  fetchEnterpriseTenant: ApiOperation<{ tenantKey: string }, EnterpriseTenant>;
  createEnterpriseTenant: ApiOperation<EnterpriseTenantCreateInput, EnterpriseTenant>;
  updateEnterpriseTenantStatus: ApiOperation<EnterpriseTenantStatusInput, EnterpriseTenant>;
  bindEnterpriseTenantMembership: ApiOperation<EnterpriseTenantMembershipInput, void>;
  bindEnterpriseTenantRepository: ApiOperation<EnterpriseTenantRepositoryInput, void>;
  bindEnterpriseTenantIdentity: ApiOperation<EnterpriseTenantIdentityInput, void>;
  fetchEnterpriseTenantQuota: ApiOperation<{ tenantKey: string }, EnterpriseTenantQuota>;
  updateEnterpriseTenantQuota: ApiOperation<EnterpriseTenantQuotaInput, EnterpriseTenantQuota>;
};

export const identityEndpoints: ApiEndpointMap<IdentityApiContract> = {
  login: {
    method: "POST",
    path: () => "/api/v1/auth/login",
    body: input => input,
    validateResponse: isAuthResponse
  },
  register: {
    method: "POST",
    path: () => "/api/v1/auth/register",
    body: input => input,
    validateResponse: isAuthResponse
  },
  getCurrentUser: {
    path: () => "/api/v1/auth/me",
    validateResponse: isCurrentUser
  },
  changePassword: {
    method: "POST",
    path: () => "/api/v1/auth/password/change",
    body: input => input
  },
  resetRefreshToken: {
    method: "POST",
    path: () => "/api/v1/auth/refresh-token/reset",
    body: input => input,
    validateResponse: isAuthResponse
  },
  logout: {
    method: "POST",
    path: () => "/api/v1/auth/logout",
    body: input => input ?? {}
  },
  fetchUsers: generatedEndpoint("userManagementControllerListUsers", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      role: input.role || undefined,
      status: input.status || undefined,
      keyword: input.keyword
    })
  }),
  fetchUserOperationAudits: generatedEndpoint("userManagementControllerListOperationAudits", {
    query: input => ({ page: input.page, pageSize: input.pageSize })
  }),
  createUser: generatedEndpoint("userManagementControllerCreateUser", {
    body: input => input
  }),
  updateUserRole: generatedEndpoint("userManagementControllerUpdateRole", {
    path: input => ({ id: input.id }),
    body: input => ({ role: input.role })
  }),
  updateUserStatus: generatedEndpoint("userManagementControllerUpdateStatus", {
    path: input => ({ id: input.id }),
    body: input => ({ status: input.status })
  }),
  fetchEnterpriseTenants: generatedEndpoint("enterpriseTenantControllerList", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      status: input.status || undefined
    })
  }),
  fetchEnterpriseTenant: generatedEndpoint("enterpriseTenantControllerGet", {
    path: input => ({ tenantKey: input.tenantKey })
  }),
  createEnterpriseTenant: generatedEndpoint("enterpriseTenantControllerCreate", {
    body: input => input
  }),
  updateEnterpriseTenantStatus: generatedEndpoint("enterpriseTenantControllerUpdateStatus", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  bindEnterpriseTenantMembership: generatedEndpoint("enterpriseTenantControllerPutMembership", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  bindEnterpriseTenantRepository: generatedEndpoint("enterpriseTenantControllerPutRepository", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  bindEnterpriseTenantIdentity: generatedEndpoint("enterpriseTenantControllerPutIdentity", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  }),
  fetchEnterpriseTenantQuota: generatedEndpoint("enterpriseTenantQuotaControllerGet", {
    path: input => ({ tenantKey: input.tenantKey })
  }),
  updateEnterpriseTenantQuota: generatedEndpoint("enterpriseTenantQuotaControllerUpdate", {
    path: input => ({ tenantKey: input.tenantKey }),
    body: input => input.payload
  })
};
