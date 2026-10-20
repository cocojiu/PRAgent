import { browserStorage } from "@/utils/browserStorage";

const ACCESS_TOKEN_STORAGE_KEY = "repoguard.accessToken";
const REFRESH_TOKEN_STORAGE_KEY = "repoguard.refreshToken";
const LEGACY_AUTH_TOKEN_STORAGE_KEY = "repoguard.authToken";
const SESSION_MARKER_STORAGE_KEY = "repoguard.session";
const SESSION_MARKER_VALUE = "active";
const CSRF_TOKEN_COOKIE_KEY = "repoguard_csrf_token";

let activeAccessToken = "";
let sessionRevision = 0;
// Only a known session may fall back to memory; false prevents stale state after logout.
let sessionMarkerOverride: boolean | undefined;
let rememberedSession = false;
let markerPersistenceFailed = false;

export const saveAuthTokens = (accessToken: string, _refreshToken: string, remember: boolean) => {
  clearAuthToken();
  activeAccessToken = accessToken;
  saveSessionMarker(remember);
};

export const saveAuthToken = (token: string, remember: boolean) => {
  saveAuthTokens(token, "", remember);
};

export const clearAuthToken = () => {
  sessionRevision++;
  activeAccessToken = "";
  sessionMarkerOverride = false;
  rememberedSession = false;
  clearStoredAuthState();
};

export const resolveAuthSessionRevision = () => sessionRevision;

export const saveRefreshedAuthToken = (accessToken: string, remember: boolean) => {
  clearStoredAuthState();
  activeAccessToken = accessToken;
  saveSessionMarker(remember);
};

const clearStoredAuthState = () => {
  markerPersistenceFailed = false;
  for (const kind of ["session", "local"] as const) {
    for (const key of [ACCESS_TOKEN_STORAGE_KEY, REFRESH_TOKEN_STORAGE_KEY,
      LEGACY_AUTH_TOKEN_STORAGE_KEY, SESSION_MARKER_STORAGE_KEY]) {
      const result = browserStorage.remove(kind, key);
      if (key === SESSION_MARKER_STORAGE_KEY && !result.available) markerPersistenceFailed = true;
    }
  }
};

export const hasAuthToken = () => Boolean(resolveAccessToken() || hasSessionMarker() || resolveRefreshToken());

export const resolveRefreshToken = () => {
  return "";
};

export const resolveCsrfToken = () => {
  if (typeof document === "undefined") {
    return "";
  }
  const encodedToken = document.cookie
    .split(";")
    .map(cookie => cookie.trim())
    .find(cookie => cookie.startsWith(`${CSRF_TOKEN_COOKIE_KEY}=`))
    ?.slice(CSRF_TOKEN_COOKIE_KEY.length + 1)
    ?? "";
  try {
    return decodeURIComponent(encodedToken);
  } catch {
    return encodedToken;
  }
};

export const resolveAccessToken = () => {
  if (typeof window === "undefined" || sessionMarkerOverride === false) {
    return "";
  }
  if (activeAccessToken) {
    return activeAccessToken;
  }
  activeAccessToken = consumeStoredAccessToken();
  return activeAccessToken;
};

const storedValue = (kind: "session" | "local", key: string) => {
  const result = browserStorage.get(kind, key);
  return result.available ? result.value || "" : "";
};

export const isSessionRemembered = () => {
  if (sessionMarkerOverride === false) return false;
  const stored = browserStorage.get("local", SESSION_MARKER_STORAGE_KEY);
  if (stored.available && !markerPersistenceFailed) rememberedSession = Boolean(stored.value);
  return rememberedSession;
};

export const hasSessionMarker = () => {
  if (sessionMarkerOverride === false) return false;
  const session = browserStorage.get("session", SESSION_MARKER_STORAGE_KEY);
  const local = browserStorage.get("local", SESSION_MARKER_STORAGE_KEY);
  const present = Boolean((session.available && session.value) || (local.available && local.value));
  if (session.available && local.available && !markerPersistenceFailed) {
    sessionMarkerOverride = present ? true : undefined;
    return present;
  }
  return present || sessionMarkerOverride === true;
};

const saveSessionMarker = (remember: boolean) => {
  rememberedSession = remember;
  const result = browserStorage.set(remember ? "local" : "session", SESSION_MARKER_STORAGE_KEY, SESSION_MARKER_VALUE);
  markerPersistenceFailed ||= !result.available;
  sessionMarkerOverride = true;
};

const consumeStoredAccessToken = () => {
  const token = storedValue("session", ACCESS_TOKEN_STORAGE_KEY)
    || storedValue("local", ACCESS_TOKEN_STORAGE_KEY)
    || storedValue("session", LEGACY_AUTH_TOKEN_STORAGE_KEY)
    || storedValue("local", LEGACY_AUTH_TOKEN_STORAGE_KEY);
  if (token) {
    for (const kind of ["session", "local"] as const) {
      browserStorage.remove(kind, ACCESS_TOKEN_STORAGE_KEY);
      browserStorage.remove(kind, LEGACY_AUTH_TOKEN_STORAGE_KEY);
    }
  }
  return token;
};
