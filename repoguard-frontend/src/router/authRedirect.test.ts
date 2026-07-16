import { describe, expect, it } from "vitest";
import { resolveSafePostAuthRedirect } from "./authRedirect";

describe("resolveSafePostAuthRedirect", () => {
  it("preserves internal RepoGuard paths with query and hash", () => {
    expect(resolveSafePostAuthRedirect("/repoguard/tasks?page=2#latest"))
      .toBe("/repoguard/tasks?page=2#latest");
  });

  it.each([
    "https://evil.example/repoguard/tasks",
    "//evil.example/repoguard/tasks",
    "/login",
    "/repoguard/../login",
    "/repoguard\\tasks",
    "javascript:alert(1)",
    "\u0000/repoguard/tasks"
  ])("rejects unsafe redirect %s", (redirect) => {
    expect(resolveSafePostAuthRedirect(redirect)).toBe("/repoguard/overview");
  });

  it("uses the overview for missing or repeated query values", () => {
    expect(resolveSafePostAuthRedirect(undefined)).toBe("/repoguard/overview");
    expect(resolveSafePostAuthRedirect(["/repoguard/tasks", "//evil.example"])).toBe("/repoguard/overview");
  });
});
