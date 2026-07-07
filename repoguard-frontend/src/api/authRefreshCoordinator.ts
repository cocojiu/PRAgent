import type { ApiResponse } from "@/api/apiEnvelope";
import {
  hasSessionMarker,
  isSessionRemembered,
  saveAuthTokens
} from "@/api/authSession";

export interface TokenPairResponse {
  accessToken: string;
}

export type AuthRefreshResult = {
  ok: boolean;
  body?: ApiResponse<TokenPairResponse>;
};

export class AuthSessionRefreshCoordinator {
  private refreshPromise: Promise<boolean> | undefined;

  constructor(private readonly refreshTokens: () => Promise<AuthRefreshResult>) {
  }

  refreshSession() {
    if (!this.refreshPromise) {
      this.refreshPromise = this.doRefreshSession().finally(() => {
        this.refreshPromise = undefined;
      });
    }
    return this.refreshPromise;
  }

  private async doRefreshSession() {
    if (!hasSessionMarker()) {
      return false;
    }
    const remember = isSessionRemembered();
    const response = await this.refreshTokens();
    if (!response.ok) {
      return false;
    }
    const body = response.body;
    if (!body?.success || !body.data?.accessToken) {
      return false;
    }
    saveAuthTokens(body.data.accessToken, "", remember);
    return true;
  }
}
