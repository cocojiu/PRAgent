const DEFAULT_POST_AUTH_REDIRECT = "/repoguard/overview";
const REDIRECT_BASE_URL = "https://repoguard.local";

export const resolveSafePostAuthRedirect = (redirect: unknown): string => {
  if (
    typeof redirect !== "string"
    || redirect.includes("\\")
    || [...redirect].some((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint <= 31 || codePoint === 127;
    })
  ) {
    return DEFAULT_POST_AUTH_REDIRECT;
  }

  try {
    const base = new URL(REDIRECT_BASE_URL);
    const target = new URL(redirect, base);
    if (target.origin !== base.origin) {
      return DEFAULT_POST_AUTH_REDIRECT;
    }
    if (target.pathname !== "/repoguard" && !target.pathname.startsWith("/repoguard/")) {
      return DEFAULT_POST_AUTH_REDIRECT;
    }
    return `${target.pathname}${target.search}${target.hash}`;
  } catch {
    return DEFAULT_POST_AUTH_REDIRECT;
  }
};
