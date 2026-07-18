import type { ApiResponse } from "@/api/apiEnvelope";
import {
  hasSessionMarker,
  isSessionRemembered,
  saveAuthTokens
} from "@/api/authSession";

const AUTH_REFRESH_LOCK_NAME = "repoguard.auth.refresh";
const AUTH_REFRESH_CHANNEL_NAME = "repoguard.auth.refresh.v1";
const DEFAULT_CROSS_TAB_RESULT_WAIT_MS = 300;

export interface TokenPairResponse {
  accessToken: string;
}

export type AuthRefreshResult = {
  ok: boolean;
  body?: ApiResponse<TokenPairResponse>;
};

export type AuthRefreshBroadcastMessage = {
  type: "auth-refresh-completed";
  completedAt: number;
  success: boolean;
  accessToken?: string;
};

export interface AuthRefreshMessageChannel {
  postMessage(message: AuthRefreshBroadcastMessage): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
}

export type CrossTabRefreshLock = (task: () => Promise<boolean>) => Promise<boolean>;

export type AuthSessionRefreshCoordinatorOptions = {
  runWithCrossTabLock?: CrossTabRefreshLock | null;
  channel?: AuthRefreshMessageChannel | null;
  now?: () => number;
  crossTabResultWaitMs?: number;
};

type SuccessWaiter = {
  requestedAt: number;
  complete: () => void;
};

export class AuthSessionRefreshCoordinator {
  private refreshPromise: Promise<boolean> | undefined;
  private readonly runWithCrossTabLock: CrossTabRefreshLock | undefined;
  private readonly channel: AuthRefreshMessageChannel | undefined;
  private readonly now: () => number;
  private readonly crossTabResultWaitMs: number;
  private readonly successWaiters = new Set<SuccessWaiter>();
  private latestCrossTabResult: AuthRefreshBroadcastMessage | undefined;

  constructor(
    private readonly refreshTokens: () => Promise<AuthRefreshResult>,
    options: AuthSessionRefreshCoordinatorOptions = {}
  ) {
    this.runWithCrossTabLock = options.runWithCrossTabLock === undefined
      ? createBrowserRefreshLock()
      : options.runWithCrossTabLock ?? undefined;
    this.channel = options.channel === undefined
      ? createBrowserRefreshChannel()
      : options.channel ?? undefined;
    this.now = options.now ?? Date.now;
    this.crossTabResultWaitMs = Math.max(0, options.crossTabResultWaitMs ?? DEFAULT_CROSS_TAB_RESULT_WAIT_MS);
    this.channel?.addEventListener("message", event => {
      this.acceptCrossTabResult(event.data);
    });
  }

  refreshSession() {
    if (!this.refreshPromise) {
      const requestedAt = this.now();
      this.refreshPromise = this.coordinateRefresh(requestedAt).finally(() => {
        this.refreshPromise = undefined;
      });
    }
    return this.refreshPromise;
  }

  private async coordinateRefresh(requestedAt: number) {
    if (!hasSessionMarker()) {
      return false;
    }
    if (!this.runWithCrossTabLock) {
      const sharedResult = this.crossTabResultSince(requestedAt);
      return sharedResult ?? this.doRefreshSession(requestedAt, true);
    }
    return this.runWithCrossTabLock(async () => {
      await yieldToBroadcastChannel(this.channel);
      const sharedResult = this.crossTabResultSince(requestedAt);
      return sharedResult ?? this.doRefreshSession(requestedAt, false);
    });
  }

  private async doRefreshSession(requestedAt: number, waitForConcurrentSuccess: boolean) {
    if (!hasSessionMarker()) {
      return false;
    }
    const remember = isSessionRemembered();
    const response = await this.refreshTokens();
    const accessToken = response.ok && response.body?.success
      ? response.body.data?.accessToken
      : undefined;
    if (!accessToken) {
      if (waitForConcurrentSuccess && await this.waitForSuccessfulCrossTabResult(requestedAt)) {
        return true;
      }
      this.publishCrossTabResult({
        type: "auth-refresh-completed",
        completedAt: this.now(),
        success: false
      });
      return false;
    }
    saveAuthTokens(accessToken, "", remember);
    this.publishCrossTabResult({
      type: "auth-refresh-completed",
      completedAt: this.now(),
      success: true,
      accessToken
    });
    return true;
  }

  private publishCrossTabResult(message: AuthRefreshBroadcastMessage) {
    this.recordCrossTabResult(message, false);
    this.channel?.postMessage(message);
  }

  private acceptCrossTabResult(value: unknown) {
    if (!isAuthRefreshBroadcastMessage(value) || !hasSessionMarker()) {
      return;
    }
    this.recordCrossTabResult(value, true);
  }

  private recordCrossTabResult(message: AuthRefreshBroadcastMessage, applyAccessToken: boolean) {
    if (this.latestCrossTabResult && this.latestCrossTabResult.completedAt > message.completedAt) {
      return;
    }
    this.latestCrossTabResult = message;
    if (applyAccessToken && message.success && message.accessToken) {
      const remember = isSessionRemembered();
      saveAuthTokens(message.accessToken, "", remember);
    }
    if (message.success) {
      this.successWaiters.forEach(waiter => {
        if (message.completedAt >= waiter.requestedAt) {
          waiter.complete();
        }
      });
    }
  }

  private crossTabResultSince(requestedAt: number) {
    if (!this.latestCrossTabResult || this.latestCrossTabResult.completedAt < requestedAt) {
      return undefined;
    }
    return this.latestCrossTabResult.success;
  }

  private async waitForSuccessfulCrossTabResult(requestedAt: number) {
    if (this.crossTabResultSince(requestedAt) === true) {
      return true;
    }
    if (!this.channel || this.crossTabResultWaitMs === 0) {
      return false;
    }
    return new Promise<boolean>(resolve => {
      let settled = false;
      const finish = (result: boolean) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timeout);
        this.successWaiters.delete(waiter);
        resolve(result);
      };
      const waiter: SuccessWaiter = {
        requestedAt,
        complete: () => finish(true)
      };
      const timeout = setTimeout(() => finish(false), this.crossTabResultWaitMs);
      this.successWaiters.add(waiter);
      if (this.crossTabResultSince(requestedAt) === true) {
        finish(true);
      }
    });
  }
}

const createBrowserRefreshLock = (): CrossTabRefreshLock | undefined => {
  if (typeof window === "undefined") {
    return undefined;
  }
  const navigatorWithLocks = window.navigator as Navigator & {
    locks?: {
      request(name: string, task: () => Promise<boolean>): Promise<boolean>;
    };
  };
  const lockManager = navigatorWithLocks.locks;
  return lockManager
    ? task => lockManager.request(AUTH_REFRESH_LOCK_NAME, task)
    : undefined;
};

const createBrowserRefreshChannel = (): AuthRefreshMessageChannel | undefined => {
  if (typeof window === "undefined" || typeof window.BroadcastChannel !== "function") {
    return undefined;
  }
  const channel = new window.BroadcastChannel(AUTH_REFRESH_CHANNEL_NAME);
  return {
    postMessage: message => channel.postMessage(message),
    addEventListener: (_type, listener) => channel.addEventListener("message", listener)
  };
};

const yieldToBroadcastChannel = async (channel: AuthRefreshMessageChannel | undefined) => {
  if (!channel) {
    return;
  }
  await new Promise<void>(resolve => setTimeout(resolve, 0));
};

const isAuthRefreshBroadcastMessage = (value: unknown): value is AuthRefreshBroadcastMessage => {
  if (!value || typeof value !== "object") {
    return false;
  }
  const message = value as Partial<AuthRefreshBroadcastMessage>;
  return message.type === "auth-refresh-completed"
    && typeof message.completedAt === "number"
    && Number.isFinite(message.completedAt)
    && typeof message.success === "boolean"
    && (!message.success || typeof message.accessToken === "string" && message.accessToken.length > 0);
};
