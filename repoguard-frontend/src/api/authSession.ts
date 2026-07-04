const ACCESS_TOKEN_STORAGE_KEY = "repoguard.accessToken";
const REFRESH_TOKEN_STORAGE_KEY = "repoguard.refreshToken";
const LEGACY_AUTH_TOKEN_STORAGE_KEY = "repoguard.authToken";
const SESSION_MARKER_STORAGE_KEY = "repoguard.session";
const SESSION_MARKER_VALUE = "active";

let activeAccessToken = "";

export const saveAuthTokens = (accessToken: string, _refreshToken: string, remember: boolean) => {
  clearAuthToken();
  activeAccessToken = accessToken;
  saveSessionMarker(remember);
};

export const saveAuthToken = (token: string, remember: boolean) => {
  saveAuthTokens(token, "", remember);
};

export const clearAuthToken = () => {
  activeAccessToken = "";
  window.sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  window.localStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
  window.sessionStorage.removeItem(SESSION_MARKER_STORAGE_KEY);
  window.localStorage.removeItem(SESSION_MARKER_STORAGE_KEY);
};

export const hasAuthToken = () => Boolean(resolveAccessToken() || hasSessionMarker() || resolveRefreshToken());

export const resolveRefreshToken = () => {
  if (typeof window === "undefined") {
    return "";
  }
  return window.sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)
    || "";
};

export const resolveAccessToken = () => {
  if (typeof window === "undefined") {
    return "";
  }
  if (activeAccessToken) {
    return activeAccessToken;
  }
  activeAccessToken = consumeStoredAccessToken();
  return activeAccessToken;
};

export const isSessionRemembered = () => Boolean(
  window.localStorage.getItem(SESSION_MARKER_STORAGE_KEY)
    || window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)
);

export const hasSessionMarker = () => Boolean(
  window.sessionStorage.getItem(SESSION_MARKER_STORAGE_KEY)
    || window.localStorage.getItem(SESSION_MARKER_STORAGE_KEY)
);

const saveSessionMarker = (remember: boolean) => {
  const storage = remember ? window.localStorage : window.sessionStorage;
  storage.setItem(SESSION_MARKER_STORAGE_KEY, SESSION_MARKER_VALUE);
};

const consumeStoredAccessToken = () => {
  const token = window.sessionStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)
    || window.sessionStorage.getItem(LEGACY_AUTH_TOKEN_STORAGE_KEY)
    || window.localStorage.getItem(LEGACY_AUTH_TOKEN_STORAGE_KEY)
    || "";
  if (token) {
    window.sessionStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    window.localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    window.sessionStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
    window.localStorage.removeItem(LEGACY_AUTH_TOKEN_STORAGE_KEY);
  }
  return token;
};
