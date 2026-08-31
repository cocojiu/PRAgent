<template>
  <div v-loading="loading" class="users-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>用户管理</h1>
        <p>管理平台账号、角色权限和账号启停状态</p>
      </div>
      <div class="page-heading-actions">
        <el-button type="primary" :icon="UserPlus" size="large" :disabled="!canManage" @click="openCreateDialog">
          创建用户
        </el-button>
        <el-button :icon="RefreshCw" size="large" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
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

      <el-table :data="users" class="rg-table task-table" size="large" aria-label="用户管理列表">
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
      <el-pagination
        class="table-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :current-page="usersPage"
        :page-size="usersPageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="usersTotal"
        @current-change="changeUsersPage"
        @size-change="changeUsersPageSize"
      />
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
      <el-pagination
        class="table-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :current-page="auditPage"
        :page-size="auditPageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="auditTotal"
        @current-change="changeAuditPage"
        @size-change="changeAuditPageSize"
      />
    </section>

    <el-dialog v-model="createDialogVisible" title="创建用户" width="480px" destroy-on-close>
      <el-form class="create-user-form" label-position="top" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="createForm.username" placeholder="请输入用户名" autocomplete="off" />
        </el-form-item>
        <el-form-item label="邮箱地址">
          <el-input v-model="createForm.email" placeholder="请输入企业邮箱" autocomplete="off" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input
            v-model="createForm.password"
            type="password"
            placeholder="至少 8 位，包含字母和数字"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input
            v-model="createForm.confirmPassword"
            type="password"
            placeholder="再次输入初始密码"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingUser" @click="submitCreateUser">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import "@/features/user-management/userManagement.css";
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { History, RefreshCw, Search, ShieldCheck, UserCheck, UserPlus, UserX, Users } from "@lucide/vue";
import { createUser, fetchUserOperationAudits, fetchUsers, updateUserRole, updateUserStatus } from "@/api/users";
import type { ManagedUser, UserCreateRequest, UserOperationAudit, UserRole, UserStatus } from "@/api/users";
import MetricGrid, { type MetricGridItem } from "@/components/MetricGrid.vue";
import { useMetricIcon } from "@/composables/useMetricIcon";
import { buildUserManagementMetrics } from "@/features/user-management/userManagementMetrics";
import { createLatestOnlyLoader } from "@/features/user-management/latestOnlyLoader";
import { canManage, currentUser } from "@/stores/authState";
import { getErrorMessage } from "@/utils/errors";
import { formatDateTime } from "@/utils/dateTime";

const loading = ref(false);
const auditLoading = ref(false);
const keyword = ref("");
const roleFilter = ref<UserRole | "">("");
const statusFilter = ref<UserStatus | "">("");
const users = ref<ManagedUser[]>([]);
const audits = ref<UserOperationAudit[]>([]);
const usersPage = ref(1);
const usersPageSize = ref(20);
const usersTotal = ref(0);
const auditPage = ref(1);
const auditPageSize = ref(20);
const auditTotal = ref(0);
const savingIds = ref<Set<number>>(new Set());
const createDialogVisible = ref(false);
const creatingUser = ref(false);
let userFilterDebounceTimer: ReturnType<typeof setTimeout> | undefined;
const createForm = reactive<UserCreateRequest>({
  username: "",
  email: "",
  password: "",
  confirmPassword: ""
});

const metricIconMap = {
  blue: Users,
  green: UserCheck,
  orange: ShieldCheck,
  red: UserX
} as const;

const getMetricIcon = useMetricIcon(metricIconMap, Users);

const userMetricItems = computed<MetricGridItem[]>(() => buildUserManagementMetrics(
  users.value,
  usersTotal.value,
  Boolean(roleFilter.value || statusFilter.value || keyword.value.trim())
));

const latestUsersLoader = createLatestOnlyLoader<Awaited<ReturnType<typeof fetchUsers>>>((page) => {
  users.value = page.items;
  usersTotal.value = page.total;
});

const loadUsers = () => latestUsersLoader.load(() => fetchUsers({
    page: usersPage.value,
    pageSize: usersPageSize.value,
    role: roleFilter.value,
    status: statusFilter.value,
    keyword: keyword.value.trim() || undefined
  }));

const loadUsersWithMessage = async () => {
  try {
    await loadUsers();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "用户列表加载失败"));
  }
};

const latestAuditsLoader = createLatestOnlyLoader<Awaited<ReturnType<typeof fetchUserOperationAudits>>>((page) => {
  audits.value = page.items;
  auditTotal.value = page.total;
});

const loadAudits = async () => {
  auditLoading.value = true;
  try {
    await latestAuditsLoader.load(() => fetchUserOperationAudits({
      page: auditPage.value,
      pageSize: auditPageSize.value
    }));
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

const changeUsersPage = async (page: number) => {
  usersPage.value = page;
  await loadUsers();
};

const changeUsersPageSize = async (pageSize: number) => {
  usersPageSize.value = pageSize;
  usersPage.value = 1;
  await loadUsers();
};

const changeAuditPage = async (page: number) => {
  auditPage.value = page;
  await loadAudits();
};

const changeAuditPageSize = async (pageSize: number) => {
  auditPageSize.value = pageSize;
  auditPage.value = 1;
  await loadAudits();
};

const openCreateDialog = () => {
  if (!canManage.value) {
    return;
  }
  resetCreateForm();
  createDialogVisible.value = true;
};

const submitCreateUser = async () => {
  if (creatingUser.value) {
    return;
  }
  if (!createForm.username.trim() || !createForm.email.trim() || !createForm.password || !createForm.confirmPassword) {
    ElMessage.warning("请完整填写用户信息");
    return;
  }
  if (createForm.password !== createForm.confirmPassword) {
    ElMessage.warning("两次输入的密码不一致");
    return;
  }
  creatingUser.value = true;
  try {
    await createUser({
      username: createForm.username.trim(),
      email: createForm.email.trim(),
      password: createForm.password,
      confirmPassword: createForm.confirmPassword
    });
    usersPage.value = 1;
    await loadUsers();
    await loadAudits();
    createDialogVisible.value = false;
    ElMessage.success("用户已创建");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "用户创建失败"));
  } finally {
    creatingUser.value = false;
  }
};

const resetCreateForm = () => {
  createForm.username = "";
  createForm.email = "";
  createForm.password = "";
  createForm.confirmPassword = "";
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
const actionText = (action: string) => {
  if (action === "USER_CREATE") {
    return "创建账号";
  }
  return action === "ROLE_UPDATE" ? "角色调整" : "账号状态";
};

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

watch([roleFilter, statusFilter], () => {
  usersPage.value = 1;
  if (userFilterDebounceTimer) {
    clearTimeout(userFilterDebounceTimer);
  }
  void loadUsersWithMessage();
});

watch(keyword, () => {
  usersPage.value = 1;
  if (userFilterDebounceTimer) {
    clearTimeout(userFilterDebounceTimer);
  }
  userFilterDebounceTimer = setTimeout(() => {
    void loadUsersWithMessage();
  }, 350);
});

onUnmounted(() => {
  if (userFilterDebounceTimer) {
    clearTimeout(userFilterDebounceTimer);
  }
  latestUsersLoader.cancel();
  latestAuditsLoader.cancel();
});

onMounted(loadAll);
</script>
