import { clearAuthToken, request, resolveRefreshToken, saveAuthTokens } from "@/api/client";

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
  const response = await request<AuthResponse>("/api/v1/auth/login", undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });
  saveAuthTokens(response.accessToken, response.refreshToken, payload.remember);
  return response;
};

export const register = async (payload: RegisterRequest) => {
  const response = await request<AuthResponse>("/api/v1/auth/register", undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });
  saveAuthTokens(response.accessToken, response.refreshToken, false);
  return response;
};

export const getCurrentUser = () => request<CurrentUser>("/api/v1/auth/me");

export const logout = async () => {
  const refreshToken = resolveRefreshToken();
  if (refreshToken) {
    try {
      await request<void>("/api/v1/auth/logout", undefined, {
        method: "POST",
        body: JSON.stringify({ refreshToken })
      });
    } finally {
      clearAuthToken();
    }
    return;
  }
  clearAuthToken();
};
