import type { ApiResponse } from "@/api/apiEnvelope";
import {
  hasSessionMarker,
  isSessionRemembered,
  resolveCsrfToken,
  saveAuthTokens
} from "@/api/authSession";

interface TokenPairResponse {
  accessToken: string;
  refreshToken?: string;
}

export class AuthSessionRefreshCoordinator {
  private refreshPromise: Promise<boolean> | undefined;

  constructor(
    private readonly buildUrl: (path: string) => string,
    private readonly credentials: RequestCredentials
  ) {
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
    const headers = new Headers();
    const csrfToken = resolveCsrfToken();
    if (csrfToken) {
      headers.set("X-RepoGuard-CSRF", csrfToken);
    }
    const response = await fetch(this.buildUrl("/api/v1/auth/refresh"), {
      method: "POST",
      headers,
      credentials: this.credentials
    });
    if (!response.ok) {
      return false;
    }
    const body = (await response.json()) as ApiResponse<TokenPairResponse>;
    if (!body.success || !body.data?.accessToken) {
      return false;
    }
    saveAuthTokens(body.data.accessToken, body.data.refreshToken ?? "", remember);
    return true;
  }
}
