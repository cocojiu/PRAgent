import { describe, expect, it } from "vitest";
import { canAccessRouteMeta } from "./accessPolicy";

describe("route access policy", () => {
  it("uses route metadata as the common authentication and management policy", () => {
    const managementRoute = { requiresAuth: true, requiresManage: true };

    expect(canAccessRouteMeta(managementRoute, {
      authenticated: true,
      managementAllowed: true,
      enterpriseEnabled: true
    })).toBe(true);
    expect(canAccessRouteMeta(managementRoute, {
      authenticated: true,
      managementAllowed: false,
      enterpriseEnabled: true
    })).toBe(false);
    expect(canAccessRouteMeta({ requiresAuth: true }, {
      authenticated: false,
      managementAllowed: false,
      enterpriseEnabled: true
    })).toBe(false);
  });

  it("hides enterprise routes from the personal edition", () => {
    const enterpriseRoute = {
      requiresAuth: true,
      requiresManage: true,
      requiresEnterprise: true
    };

    expect(canAccessRouteMeta(enterpriseRoute, {
      authenticated: true,
      managementAllowed: true,
      enterpriseEnabled: false
    })).toBe(false);
    expect(canAccessRouteMeta(enterpriseRoute, {
      authenticated: true,
      managementAllowed: true,
      enterpriseEnabled: true
    })).toBe(true);
  });

  it("limits the enterprise tenant console to platform administrators", () => {
    const tenantRoute = {
      requiresAuth: true,
      requiresManage: true,
      requiresEnterprise: true,
      requiresRole: ["ADMIN", "PLATFORM_ADMIN"]
    };

    expect(canAccessRouteMeta(tenantRoute, {
      authenticated: true,
      managementAllowed: true,
      enterpriseEnabled: true,
      role: "PLATFORM_ADMIN"
    })).toBe(true);
    expect(canAccessRouteMeta(tenantRoute, {
      authenticated: true,
      managementAllowed: true,
      enterpriseEnabled: true,
      role: "TENANT_ADMIN"
    })).toBe(false);
  });
});
