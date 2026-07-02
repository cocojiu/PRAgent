import { apiRequest } from "@/api/contracts";

export type UserRole = "ADMIN" | "VIEWER";
export type UserStatus = "ACTIVE" | "DISABLED";

export interface ManagedUser {
  id: number;
  username: string;
  email: string;
  role: UserRole;
  status: UserStatus;
  failedLoginCount: number;
  lockedUntil?: string;
  lastLoginAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface UserCreateRequest {
  username: string;
  email: string;
  password: string;
  confirmPassword: string;
}

export type UserOperationAction = "ROLE_UPDATE" | "STATUS_UPDATE" | string;

export interface UserOperationAudit {
  id: number;
  operatorUserId?: number;
  operatorUsername?: string;
  targetUserId: number;
  targetUsername: string;
  action: UserOperationAction;
  beforeValue?: string;
  afterValue?: string;
  clientIp?: string;
  userAgent?: string;
  createdAt: string;
}

export const fetchUsers = () => apiRequest("fetchUsers", undefined);

export const fetchUserOperationAudits = () => apiRequest("fetchUserOperationAudits", undefined);

export const createUser = (payload: UserCreateRequest) => apiRequest("createUser", payload);

export const updateUserRole = (id: number, role: UserRole) =>
  apiRequest("updateUserRole", { id, role });

export const updateUserStatus = (id: number, status: UserStatus) =>
  apiRequest("updateUserStatus", { id, status });
