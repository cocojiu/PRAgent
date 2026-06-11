import { request } from "@/api/client";

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

export const fetchUsers = () => request<ManagedUser[]>("/api/v1/users");

export const fetchUserOperationAudits = () => request<UserOperationAudit[]>("/api/v1/users/audits");

export const updateUserRole = (id: number, role: UserRole) =>
  request<ManagedUser>(`/api/v1/users/${id}/role`, undefined, {
    method: "PUT",
    body: JSON.stringify({ role })
  });

export const updateUserStatus = (id: number, status: UserStatus) =>
  request<ManagedUser>(`/api/v1/users/${id}/status`, undefined, {
    method: "PUT",
    body: JSON.stringify({ status })
  });
