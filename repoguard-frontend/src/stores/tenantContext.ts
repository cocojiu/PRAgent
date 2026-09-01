import { computed, ref } from "vue";

const ACTIVE_TENANT_KEY = "repoguard-active-tenant";
const activeTenantKey = ref(readStoredTenantKey());

export const activeTenant = computed(() => activeTenantKey.value);

export const setActiveTenant = (tenantKey?: string | null) => {
  const normalized = tenantKey?.trim().toLowerCase() || "";
  activeTenantKey.value = normalized;
  if (normalized) {
    window.localStorage.setItem(ACTIVE_TENANT_KEY, normalized);
  } else {
    window.localStorage.removeItem(ACTIVE_TENANT_KEY);
  }
};

export const clearActiveTenant = () => setActiveTenant();

export const resetActiveTenantFromStorage = () => {
  activeTenantKey.value = readStoredTenantKey();
};

function readStoredTenantKey() {
  if (typeof window === "undefined") {
    return "";
  }
  return window.localStorage.getItem(ACTIVE_TENANT_KEY)?.trim().toLowerCase() || "";
}
