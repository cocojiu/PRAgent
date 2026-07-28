import { describe, expect, it } from "vitest";
import { canAccessRouteMeta } from "./accessPolicy";

describe("route access policy", () => {
  it("uses route metadata as the common authentication and management policy", () => {
    const managementRoute = { requiresAuth: true, requiresManage: true };

    expect(canAccessRouteMeta(managementRoute, {
      authenticated: true,
      managementAllowed: true
    })).toBe(true);
    expect(canAccessRouteMeta(managementRoute, {
      authenticated: true,
      managementAllowed: false
    })).toBe(false);
    expect(canAccessRouteMeta({ requiresAuth: true }, {
      authenticated: false,
      managementAllowed: false
    })).toBe(false);
  });
});
