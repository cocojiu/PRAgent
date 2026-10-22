import { computed, ref } from "vue";
import { browserStorage } from "@/utils/browserStorage";
import { resetFrontendPerformanceBoundary } from "@/observability/frontendPerformanceBuffer";

const ACTIVE_TENANT_KEY = "repoguard-active-tenant";
let pendingTenantKey: string | undefined;
const activeTenantKey = ref(readStoredTenantKey());

export const activeTenant = computed(() => activeTenantKey.value);

export const setActiveTenant = (tenantKey?: string | null) => {
  const normalized = tenantKey?.trim().toLowerCase() || "";
  if (activeTenantKey.value !== normalized) resetFrontendPerformanceBoundary();
  activeTenantKey.value = normalized;
  const result = normalized
    ? browserStorage.set("local", ACTIVE_TENANT_KEY, normalized)
    : browserStorage.remove("local", ACTIVE_TENANT_KEY);
  pendingTenantKey = result.available ? undefined : normalized;
};

export const clearActiveTenant = () => setActiveTenant();

export const resetActiveTenantFromStorage = () => {
  // A failed write/removal must not resurrect an older stored tenant selection.
  if (pendingTenantKey !== undefined) {
    setActiveTenant(pendingTenantKey);
    return;
  }
  const stored = readStoredTenantKey(activeTenantKey.value);
  if (stored !== activeTenantKey.value) resetFrontendPerformanceBoundary();
  activeTenantKey.value = stored;
};

function readStoredTenantKey(fallback = "") {
  const stored = browserStorage.get("local", ACTIVE_TENANT_KEY);
  return stored.available ? stored.value?.trim().toLowerCase() || "" : fallback;
}
