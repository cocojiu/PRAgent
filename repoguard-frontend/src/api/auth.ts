import { clearAuthToken, resolveRefreshToken, saveAuthTokens } from "@/api/client";
import { apiRequest } from "@/api/contracts";

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  role: string;
}

export interface CurrentUser extends AuthUser {
  status: string;
  lastLoginAt?: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
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

export const login = async (payload: LoginRequest) => {
  const response = await apiRequest("login", payload);
  saveAuthTokens(response.accessToken, response.refreshToken, payload.remember);
  return response;
};

export const register = async (payload: RegisterRequest) => {
  const response = await apiRequest("register", payload);
  saveAuthTokens(response.accessToken, response.refreshToken, false);
  return response;
};

export const getCurrentUser = () => apiRequest("getCurrentUser", undefined);

export const logout = async () => {
  const refreshToken = resolveRefreshToken();
  if (refreshToken) {
    try {
      await apiRequest("logout", { refreshToken });
    } finally {
      clearAuthToken();
    }
    return;
  }
  clearAuthToken();
};
