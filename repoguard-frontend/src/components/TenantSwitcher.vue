<template>
  <div v-if="canManageTenants" class="tenant-switcher" aria-label="当前租户">
    <span class="tenant-switcher-label">租户</span>
    <select
      v-model="selectedTenantKey"
      class="tenant-switcher-select"
      aria-label="选择租户"
      :disabled="loading"
      @change="changeTenant"
    >
      <option value="">选择租户</option>
      <option
        v-for="tenant in tenants"
        :key="tenant.tenantKey"
        :value="tenant.tenantKey"
      >{{ `${tenant.displayName} (${tenant.tenantKey})` }}</option>
    </select>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchEnterpriseTenants } from "@/api/enterpriseTenants";
import type { EnterpriseTenant } from "@/types";
import { canManageTenants } from "@/stores/authState";
import { activeTenant, setActiveTenant } from "@/stores/tenantContext";

const tenants = ref<EnterpriseTenant[]>([]);
const selectedTenantKey = ref(activeTenant.value);
const loading = ref(false);
let loaded = false;

const loadTenants = async () => {
  if (!canManageTenants.value || loading.value || loaded) {
    return;
  }
  loading.value = true;
  try {
    const page = await fetchEnterpriseTenants({ page: 1, pageSize: 100, status: "ACTIVE" });
    tenants.value = page.items;
    loaded = true;
    if (selectedTenantKey.value && !page.items.some(tenant => tenant.tenantKey === selectedTenantKey.value)) {
      setActiveTenant();
      selectedTenantKey.value = "";
    }
  } catch {
    ElMessage.warning("租户列表加载失败，可稍后重试");
  } finally {
    loading.value = false;
  }
};

const changeTenant = (event: Event) => {
  const tenantKey = (event.target as HTMLSelectElement).value || undefined;
  setActiveTenant(tenantKey);
  ElMessage.success(tenantKey ? `已切换到租户 ${tenantKey}` : "已清除租户选择");
};

watch(activeTenant, value => {
  selectedTenantKey.value = value;
});
watch(canManageTenants, value => {
  if (value) {
    void loadTenants();
  }
});

onMounted(() => {
  void loadTenants();
});
</script>

<style scoped>
.tenant-switcher {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-right: 12px;
}

.tenant-switcher-label {
  color: #64748b;
  font-size: 12px;
  white-space: nowrap;
}

.tenant-switcher-select {
  width: 190px;
}
</style>
