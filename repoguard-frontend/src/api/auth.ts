import { clearAuthToken, hasAuthToken, saveAuthTokens } from "@/api/client";
import { apiRequest } from "@/api/contracts";
import type { ApiRequestOptions } from "@/api/contracts";

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  role: string;
}

export interface CurrentUser extends AuthUser {
  status: string;
  lastLoginAt?: string;
  language?: string;
  timezone?: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  accessTokenExpiresInSeconds: number;
  refreshTokenExpiresInSeconds: number;
  user: AuthUser;
}

export interface LoginRequest {
  account: string;
  password: string;
  remember: boolean;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  confirmPassword: string;
}

export interface PasswordChangeRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface RefreshTokenResetRequest {
  account: string;
  password: string;
  remember?: boolean;
}

export const login = async (payload: LoginRequest) => {
  const response = await apiRequest("login", payload);
  saveAuthTokens(response.accessToken, "", payload.remember);
  return response;
};

export const register = async (payload: RegisterRequest) => {
  const response = await apiRequest("register", payload);
  saveAuthTokens(response.accessToken, "", false);
  return response;
};

export const getCurrentUser = (options: ApiRequestOptions = {}) =>
  apiRequest("getCurrentUser", undefined, options);

export const changePassword = async (payload: PasswordChangeRequest) => {
  await apiRequest("changePassword", payload);
  clearAuthToken();
};

export const logout = async () => {
  if (hasAuthToken()) {
    try {
      await apiRequest("logout", undefined);
    } finally {
      clearAuthToken();
    }
    return;
  }
  clearAuthToken();
};
