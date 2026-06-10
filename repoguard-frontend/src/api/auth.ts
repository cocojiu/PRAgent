import { request, saveAuthToken } from "@/api/client";

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  role: string;
}

export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
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
  saveAuthToken(response.token, payload.remember);
  return response;
};

export const register = async (payload: RegisterRequest) => {
  const response = await request<AuthResponse>("/api/v1/auth/register", undefined, {
    method: "POST",
    body: JSON.stringify(payload)
  });
  saveAuthToken(response.token, false);
  return response;
};
