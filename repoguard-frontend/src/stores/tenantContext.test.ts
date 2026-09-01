import { afterEach, describe, expect, it } from "vitest";
import { activeTenant, clearActiveTenant, resetActiveTenantFromStorage, setActiveTenant } from "./tenantContext";

describe("tenant context", () => {
  afterEach(() => {
    clearActiveTenant();
    window.localStorage.clear();
  });

  it("normalizes and persists the selected tenant key", () => {
    setActiveTenant("  Acme-Prod ");

    expect(activeTenant.value).toBe("acme-prod");
    expect(window.localStorage.getItem("repoguard-active-tenant")).toBe("acme-prod");
  });

  it("clears and restores a tenant selection from storage", () => {
    window.localStorage.setItem("repoguard-active-tenant", "  acme ");
    resetActiveTenantFromStorage();
    expect(activeTenant.value).toBe("acme");

    clearActiveTenant();
    expect(activeTenant.value).toBe("");
    expect(window.localStorage.getItem("repoguard-active-tenant")).toBeNull();
  });
});
