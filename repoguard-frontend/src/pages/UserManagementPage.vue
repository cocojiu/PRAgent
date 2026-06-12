<template>
  <div v-loading="loading" class="users-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>用户管理</h1>
        <p>管理平台账号、角色权限和账号启停状态</p>
      </div>
      <el-button :icon="RefreshCw" size="large" :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <MetricGrid :metrics="userMetricItems" :resolve-icon="getMetricIcon" />

    <section class="user-panel">
      <div class="filter-bar user-filter">
        <el-select v-model="roleFilter" placeholder="全部角色" clearable>
          <el-option label="全部角色" value="" />
          <el-option label="管理员" value="ADMIN" />
          <el-option label="观察员" value="VIEWER" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable>
          <el-option label="全部状态" value="" />
          <el-option label="启用" value="ACTIVE" />
          <el-option label="禁用" value="DISABLED" />
        </el-select>
        <el-input v-model="keyword" class="search-input" placeholder="搜索用户名或邮箱" clearable>
          <template #suffix><Search :size="18" /></template>
        </el-input>
      </div>

      <el-table :data="filteredUsers" class="rg-table task-table" size="large" aria-label="用户管理列表">
        <el-table-column label="账号" min-width="220">
          <template #default="{ row }">
            <div class="user-account-cell">
              <span class="avatar small">{{ row.username.charAt(0).toUpperCase() }}</span>
              <div>
                <strong>{{ row.username }}</strong>
                <span>{{ row.email }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="150">
          <template #default="{ row }">
            <el-select
              v-model="row.role"
              :disabled="!canManage || isSaving(row.id)"
              size="small"
              @change="changeRole(row)"
            >
              <el-option label="管理员" value="ADMIN" />
              <el-option label="观察员" value="VIEWER" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <span :class="`status-pill ${statusClass(row.status)}`">{{ statusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败次数" width="110">
          <template #default="{ row }">
            <span :class="{ 'danger-count': row.failedLoginCount >= 3 }">{{ row.failedLoginCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="锁定状态" min-width="170">
          <template #default="{ row }">
            <span v-if="row.lockedUntil" class="status-pill warning">锁定至 {{ formatDateTime(row.lockedUntil) }}</span>
            <span v-else class="muted-text">未锁定</span>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :type="row.status === 'ACTIVE' ? 'danger' : 'primary'"
              plain
              :disabled="!canManage || row.id === currentUser?.id"
              :loading="savingIds.has(row.id)"
              @click="toggleStatus(row)"
            >
              {{ row.status === "ACTIVE" ? "禁用" : "启用" }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无用户账号" />
        </template>
      </el-table>
    </section>

    <section class="user-panel audit-panel">
      <div class="panel-heading">
        <div>
          <h2>最近操作记录</h2>
          <p>记录角色调整、账号启用与禁用操作，便于追溯账号权限变更。</p>
        </div>
        <el-button :icon="History" :loading="auditLoading" @click="loadAudits">刷新记录</el-button>
      </div>

      <el-table :data="audits" class="rg-table task-table" size="large" aria-label="用户操作审计列表">
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作者" min-width="150">
          <template #default="{ row }">{{ row.operatorUsername || "-" }}</template>
        </el-table-column>
        <el-table-column label="目标账号" min-width="150">
          <template #default="{ row }">{{ row.targetUsername }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <span class="status-pill info">{{ actionText(row.action) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变更内容" min-width="180">
          <template #default="{ row }">{{ valueText(row.beforeValue) }} -> {{ valueText(row.afterValue) }}</template>
        </el-table-column>
        <el-table-column label="来源 IP" min-width="140">
          <template #default="{ row }">{{ row.clientIp || "-" }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无操作记录" />
        </template>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { History, RefreshCw, Search, ShieldCheck, UserCheck, UserX, Users } from "lucide-vue-next";
import { fetchUserOperationAudits, fetchUsers, updateUserRole, updateUserStatus } from "@/api/users";
import type { ManagedUser, UserOperationAudit, UserStatus } from "@/api/users";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import { canManage, currentUser } from "@/stores/authState";
import { getErrorMessage } from "@/utils/errors";

const loading = ref(false);
const auditLoading = ref(false);
const keyword = ref("");
const roleFilter = ref("");
const statusFilter = ref("");
const users = ref<ManagedUser[]>([]);
const audits = ref<UserOperationAudit[]>([]);
const savingIds = ref<Set<number>>(new Set());

const metricIconMap = {
  blue: Users,
  green: UserCheck,
  orange: ShieldCheck,
  red: UserX
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, Users);

const filteredUsers = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return users.value.filter((user) => {
    const matchesRole = !roleFilter.value || user.role === roleFilter.value;
    const matchesStatus = !statusFilter.value || user.status === statusFilter.value;
    const matchesKeyword =
      !query || user.username.toLowerCase().includes(query) || user.email.toLowerCase().includes(query);
    return matchesRole && matchesStatus && matchesKeyword;
  });
});

const userMetricItems = computed<MetricGridItem[]>(() => {
  const total = users.value.length;
  const active = users.value.filter((user) => user.status === "ACTIVE").length;
  const admins = users.value.filter((user) => user.role === "ADMIN").length;
  const disabled = users.value.filter((user) => user.status === "DISABLED").length;
  return [
    { label: "账号总数", value: String(total), note: "已创建账号", color: "blue" },
    { label: "启用账号", value: String(active), note: "可正常登录", color: "green" },
    { label: "管理员", value: String(admins), note: "拥有管理权限", color: "orange" },
    { label: "禁用账号", value: String(disabled), note: "禁止登录", color: "red" }
  ];
});

const loadUsers = async () => {
  users.value = await fetchUsers();
};

const loadAudits = async () => {
  auditLoading.value = true;
  try {
    audits.value = await fetchUserOperationAudits();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "用户管理操作失败"));
  } finally {
    auditLoading.value = false;
  }
};

const loadAll = async () => {
  loading.value = true;
  try {
    await Promise.all([loadUsers(), loadAudits()]);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "用户管理操作失败"));
  } finally {
    loading.value = false;
  }
};

const isSaving = (id: number) => savingIds.value.has(id);

const setSaving = (id: number, saving: boolean) => {
  const next = new Set(savingIds.value);
  if (saving) {
    next.add(id);
  } else {
    next.delete(id);
  }
  savingIds.value = next;
};

const changeRole = async (user: ManagedUser) => {
  if (isSaving(user.id)) {
    await loadUsers();
    return;
  }
  if (!canManage.value) {
    await loadUsers();
    return;
  }
  const nextRole = user.role;
  setSaving(user.id, true);
  try {
    const updated = await updateUserRole(user.id, nextRole);
    applyUser(updated);
    await loadAudits();
    ElMessage.success("用户角色已更新");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "用户管理操作失败"));
    await loadUsers();
  } finally {
    setSaving(user.id, false);
  }
};

const toggleStatus = async (user: ManagedUser) => {
  if (!canManage.value || isSaving(user.id)) {
    return;
  }
  const nextStatus: UserStatus = user.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
  setSaving(user.id, true);
  try {
    const updated = await updateUserStatus(user.id, nextStatus);
    applyUser(updated);
    await loadAudits();
    ElMessage.success(nextStatus === "ACTIVE" ? "账号已启用" : "账号已禁用");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "用户管理操作失败"));
  } finally {
    setSaving(user.id, false);
  }
};

const applyUser = (updated: ManagedUser) => {
  users.value = users.value.map((user) => (user.id === updated.id ? updated : user));
};

const statusText = (status: UserStatus) => (status === "ACTIVE" ? "启用" : "禁用");
const statusClass = (status: UserStatus) => (status === "ACTIVE" ? "success" : "danger");
const actionText = (action: string) => (action === "ROLE_UPDATE" ? "角色调整" : "账号状态");

const valueText = (value?: string) => {
  if (!value) {
    return "-";
  }
  const labels: Record<string, string> = {
    ADMIN: "管理员",
    VIEWER: "观察员",
    ACTIVE: "启用",
    DISABLED: "禁用"
  };
  return labels[value] || value;
};

const formatDateTime = (value?: string) => {
  if (!value) {
    return "-";
  }
  return value.replace("T", " ").slice(0, 16);
};

onMounted(loadAll);
</script>
