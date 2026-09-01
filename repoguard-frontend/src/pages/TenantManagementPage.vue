<template>
  <div v-loading="loading" class="tenants-page">
    <div class="page-heading page-heading-row">
      <div>
        <h1>租户与仓库</h1>
        <p>创建企业租户，绑定成员、GitHub App 仓库与 OIDC 身份，并管理每日审查配额。</p>
      </div>
      <div class="page-heading-actions">
        <el-button type="primary" :icon="Plus" size="large" @click="createDialogVisible = true">创建租户</el-button>
        <el-button :icon="RefreshCw" size="large" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <div class="tenant-metrics">
      <div class="tenant-metric"><span>租户总数</span><strong>{{ tenantsTotal }}</strong></div>
      <div class="tenant-metric"><span>活跃租户</span><strong>{{ activeTenantCount }}</strong></div>
      <div class="tenant-metric"><span>已暂停</span><strong>{{ suspendedTenantCount }}</strong></div>
      <div class="tenant-metric"><span>当前租户</span><strong>{{ selectedTenant?.tenantKey || "未选择" }}</strong></div>
    </div>

    <section class="tenant-panel">
      <div class="tenant-toolbar">
        <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="loadTenants">
          <el-option label="全部状态" value="" />
          <el-option label="活跃" value="ACTIVE" />
          <el-option label="已暂停" value="SUSPENDED" />
        </el-select>
        <span class="tenant-toolbar-hint">平台管理员可在此维护跨租户绑定；审查请求会使用顶部租户切换器选择的上下文。</span>
      </div>
      <el-table :data="tenants" class="rg-table" size="large" aria-label="企业租户列表" @row-click="selectTenant">
        <el-table-column label="租户" min-width="220">
          <template #default="{ row }">
            <div class="tenant-name-cell">
              <strong>{{ row.displayName }}</strong>
              <span>{{ row.tenantKey }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span :class="`status-pill ${row.status === 'ACTIVE' ? 'success' : 'danger'}`">
              {{ row.status === "ACTIVE" ? "活跃" : "已暂停" }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="statusVersion" label="版本" width="90" />
        <el-table-column label="更新时间" min-width="180">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click.stop="selectTenant(row)">
              {{ selectedTenantKey === row.tenantKey ? "已选中" : "管理" }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无企业租户" /></template>
      </el-table>
      <el-pagination
        class="table-pagination"
        background
        layout="total, sizes, prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="tenantsTotal"
        @current-change="changePage"
        @size-change="changePageSize"
      />
    </section>

    <section v-if="selectedTenant" class="tenant-panel tenant-detail-panel">
      <div class="panel-heading">
        <div>
          <h2>{{ selectedTenant.displayName }}</h2>
          <p>{{ selectedTenant.tenantKey }} · 状态版本 {{ selectedTenant.statusVersion }}</p>
        </div>
        <div class="detail-actions">
          <el-button
            :type="selectedTenant.status === 'ACTIVE' ? 'danger' : 'success'"
            :loading="savingStatus"
            @click="toggleTenantStatus"
          >
            {{ selectedTenant.status === "ACTIVE" ? "暂停租户" : "恢复租户" }}
          </el-button>
          <el-button plain @click="setActiveTenant(selectedTenant.tenantKey)">设为当前租户</el-button>
        </div>
      </div>

      <div class="tenant-detail-grid">
        <div class="tenant-form-card">
          <h3>每日审查配额</h3>
          <p class="form-hint">已用 {{ quota?.usedReviews ?? 0 }} / {{ quota?.maxDailyReviews ?? 0 }} 次</p>
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="每日上限">
              <el-input-number v-model="quotaForm.maxDailyReviews" :min="1" :max="1000000" controls-position="right" />
            </el-form-item>
            <el-button type="primary" :loading="savingQuota" @click="saveQuota">保存配额</el-button>
          </el-form>
        </div>

        <div class="tenant-form-card">
          <h3>GitHub App 仓库绑定</h3>
          <p class="form-hint">同一 installation 不能重复分配给其他租户。</p>
          <el-form label-position="top" @submit.prevent>
            <div class="form-row">
              <el-form-item label="组织"><el-input v-model="repositoryForm.organization" placeholder="openai" /></el-form-item>
              <el-form-item label="仓库"><el-input v-model="repositoryForm.repository" placeholder="repoguard" /></el-form-item>
            </div>
            <el-form-item label="GitHub installation ID"><el-input-number v-model="repositoryForm.githubInstallationId" :min="1" controls-position="right" /></el-form-item>
            <el-button type="primary" :loading="savingRepository" @click="saveRepository">绑定仓库</el-button>
          </el-form>
        </div>

        <div class="tenant-form-card">
          <h3>成员绑定</h3>
          <p class="form-hint">成员角色会作用于当前租户上下文。</p>
          <el-form label-position="top" @submit.prevent>
            <div class="form-row">
              <el-form-item label="用户 ID"><el-input-number v-model="membershipForm.userId" :min="1" controls-position="right" /></el-form-item>
              <el-form-item label="角色">
                <el-select v-model="membershipForm.role">
                  <el-option v-for="option in membershipRoleOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
              </el-form-item>
            </div>
            <el-checkbox v-model="membershipForm.defaultTenant">设为默认租户</el-checkbox>
            <div><el-button type="primary" :loading="savingMembership" @click="saveMembership">保存成员</el-button></div>
          </el-form>
        </div>

        <div class="tenant-form-card">
          <h3>OIDC 身份绑定</h3>
          <p class="form-hint">issuer 必须是 HTTPS 地址，subject 使用 IdP 的稳定标识。</p>
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="用户 ID"><el-input-number v-model="identityForm.userId" :min="1" controls-position="right" /></el-form-item>
            <el-form-item label="Issuer"><el-input v-model="identityForm.issuer" placeholder="https://idp.example.com" /></el-form-item>
            <el-form-item label="Subject"><el-input v-model="identityForm.subject" placeholder="00u123" /></el-form-item>
            <el-button type="primary" :loading="savingIdentity" @click="saveIdentity">绑定身份</el-button>
          </el-form>
        </div>
      </div>
    </section>

    <el-dialog v-model="createDialogVisible" title="创建企业租户" width="480px" destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="租户 Key"><el-input v-model="createForm.tenantKey" placeholder="acme-prod" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="createForm.displayName" placeholder="Acme 生产环境" /></el-form-item>
        <el-form-item label="初始管理员用户 ID"><el-input-number v-model="createForm.initialAdminUserId" :min="1" controls-position="right" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createTenant">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="statusDialogVisible" title="确认租户状态变更" width="420px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="变更原因"><el-input v-model="statusReason" type="textarea" :rows="3" maxlength="512" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingStatus" @click="saveTenantStatus">确认变更</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import "@/features/tenant-management/tenantManagement.css";
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { Plus, RefreshCw } from "@lucide/vue";
import {
  bindEnterpriseTenantIdentity,
  bindEnterpriseTenantMembership,
  bindEnterpriseTenantRepository,
  createEnterpriseTenant,
  fetchEnterpriseTenant,
  fetchEnterpriseTenantQuota,
  fetchEnterpriseTenants,
  updateEnterpriseTenantQuota,
  updateEnterpriseTenantStatus
} from "@/api/enterpriseTenants";
import type {
  EnterpriseIdentityBindingRequest,
  EnterpriseRole,
  EnterpriseTenant,
  EnterpriseTenantMembershipRequest,
  EnterpriseTenantQuotaRequest,
  EnterpriseTenantRepositoryRequest,
  EnterpriseTenantStatus,
  EnterpriseTenantStatusRequest
} from "@/types";
import { activeTenant, setActiveTenant as persistActiveTenant } from "@/stores/tenantContext";
import { formatDateTime } from "@/utils/dateTime";
import { getErrorMessage } from "@/utils/errors";

const loading = ref(false);
const creating = ref(false);
const savingStatus = ref(false);
const savingQuota = ref(false);
const savingRepository = ref(false);
const savingMembership = ref(false);
const savingIdentity = ref(false);
const createDialogVisible = ref(false);
const statusDialogVisible = ref(false);
const statusReason = ref("");
const statusFilter = ref<EnterpriseTenantStatus | "">("");
const page = ref(1);
const pageSize = ref(20);
const tenants = ref<EnterpriseTenant[]>([]);
const tenantsTotal = ref(0);
const selectedTenantKey = ref(activeTenant.value);
const selectedTenant = ref<EnterpriseTenant>();
const quota = ref<Awaited<ReturnType<typeof fetchEnterpriseTenantQuota>>>();

const createForm = reactive({ tenantKey: "", displayName: "", initialAdminUserId: 1 });
const quotaForm = reactive({ maxDailyReviews: 1000 });
const repositoryForm = reactive<EnterpriseTenantRepositoryRequest>({ organization: "", repository: "", githubInstallationId: 1 });
const membershipForm = reactive<EnterpriseTenantMembershipRequest>({ userId: 1, role: "TENANT_ADMIN", defaultTenant: false });
const identityForm = reactive<EnterpriseIdentityBindingRequest>({ userId: 1, issuer: "", subject: "" });

const membershipRoleOptions: Array<{ label: string; value: Exclude<EnterpriseRole, "PLATFORM_ADMIN"> }> = [
  { label: "租户管理员", value: "TENANT_ADMIN" },
  { label: "规则管理员", value: "RULE_ADMIN" },
  { label: "审查员", value: "REVIEWER" },
  { label: "只读用户", value: "READ_ONLY" },
  { label: "管理员（兼容）", value: "ADMIN" },
  { label: "观察员（兼容）", value: "VIEWER" }
];

const activeTenantCount = computed(() => tenants.value.filter(tenant => tenant.status === "ACTIVE").length);
const suspendedTenantCount = computed(() => tenants.value.filter(tenant => tenant.status === "SUSPENDED").length);

const loadTenants = async () => {
  loading.value = true;
  try {
    const result = await fetchEnterpriseTenants({ page: page.value, pageSize: pageSize.value, status: statusFilter.value });
    tenants.value = result.items;
    tenantsTotal.value = result.total;
    if (!selectedTenantKey.value && result.items.length) {
      await selectTenant(result.items[0]);
    } else if (selectedTenantKey.value) {
      const row = result.items.find(tenant => tenant.tenantKey === selectedTenantKey.value);
      if (row) {
        await selectTenant(row);
      }
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "租户列表加载失败"));
  } finally {
    loading.value = false;
  }
};

const loadAll = async () => loadTenants();

const selectTenant = async (tenant: EnterpriseTenant) => {
  selectedTenantKey.value = tenant.tenantKey;
  persistActiveTenant(tenant.tenantKey);
  selectedTenant.value = tenant;
  try {
    const [detail, detailQuota] = await Promise.all([
      fetchEnterpriseTenant(tenant.tenantKey),
      fetchEnterpriseTenantQuota(tenant.tenantKey)
    ]);
    selectedTenant.value = detail;
    quota.value = detailQuota;
    quotaForm.maxDailyReviews = detailQuota.maxDailyReviews;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "租户详情加载失败"));
  }
};

const setActiveTenant = (tenantKey: string) => {
  selectedTenantKey.value = tenantKey;
  persistActiveTenant(tenantKey);
  ElMessage.success(`已切换到租户 ${tenantKey}`);
};

const changePage = async (nextPage: number) => {
  page.value = nextPage;
  await loadTenants();
};

const changePageSize = async (nextPageSize: number) => {
  pageSize.value = nextPageSize;
  page.value = 1;
  await loadTenants();
};

const createTenant = async () => {
  if (!createForm.tenantKey.trim() || !createForm.displayName.trim() || createForm.initialAdminUserId < 1) {
    ElMessage.warning("请完整填写租户信息");
    return;
  }
  creating.value = true;
  try {
    await createEnterpriseTenant({
      tenantKey: createForm.tenantKey.trim().toLowerCase(),
      displayName: createForm.displayName.trim(),
      initialAdminUserId: createForm.initialAdminUserId
    });
    createDialogVisible.value = false;
    ElMessage.success("租户已创建");
    await loadTenants();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "租户创建失败"));
  } finally {
    creating.value = false;
  }
};

const toggleTenantStatus = () => {
  if (!selectedTenant.value) return;
  statusReason.value = "";
  statusDialogVisible.value = true;
};

const saveTenantStatus = async () => {
  const tenant = selectedTenant.value;
  if (!tenant || !statusReason.value.trim()) {
    ElMessage.warning("请填写状态变更原因");
    return;
  }
  savingStatus.value = true;
  const payload: EnterpriseTenantStatusRequest = {
    expectedStatus: tenant.status,
    targetStatus: tenant.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE",
    expectedVersion: tenant.statusVersion,
    reason: statusReason.value.trim()
  };
  try {
    selectedTenant.value = await updateEnterpriseTenantStatus(tenant.tenantKey, payload);
    statusDialogVisible.value = false;
    tenants.value = tenants.value.map(row => row.tenantKey === tenant.tenantKey ? selectedTenant.value! : row);
    ElMessage.success("租户状态已更新");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "租户状态更新失败，请刷新后重试"));
  } finally {
    savingStatus.value = false;
  }
};

const saveQuota = async () => {
  const tenant = selectedTenant.value;
  if (!tenant || !quota.value) return;
  savingQuota.value = true;
  try {
    const payload: EnterpriseTenantQuotaRequest = {
      expectedVersion: quota.value.quotaVersion,
      maxDailyReviews: quotaForm.maxDailyReviews
    };
    quota.value = await updateEnterpriseTenantQuota(tenant.tenantKey, payload);
    ElMessage.success("审查配额已更新");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "配额更新失败，请刷新后重试"));
  } finally {
    savingQuota.value = false;
  }
};

const saveRepository = async () => {
  const tenant = selectedTenant.value;
  if (!tenant || !repositoryForm.organization.trim() || !repositoryForm.repository.trim() || repositoryForm.githubInstallationId < 1) {
    ElMessage.warning("请完整填写仓库绑定信息");
    return;
  }
  savingRepository.value = true;
  try {
    await bindEnterpriseTenantRepository(tenant.tenantKey, {
      organization: repositoryForm.organization.trim(),
      repository: repositoryForm.repository.trim(),
      githubInstallationId: repositoryForm.githubInstallationId
    });
    ElMessage.success("GitHub App 仓库已绑定");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "仓库绑定失败"));
  } finally {
    savingRepository.value = false;
  }
};

const saveMembership = async () => {
  const tenant = selectedTenant.value;
  if (!tenant || membershipForm.userId < 1) {
    ElMessage.warning("请输入有效的用户 ID");
    return;
  }
  savingMembership.value = true;
  try {
    await bindEnterpriseTenantMembership(tenant.tenantKey, { ...membershipForm });
    ElMessage.success("租户成员已保存");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "成员绑定失败"));
  } finally {
    savingMembership.value = false;
  }
};

const saveIdentity = async () => {
  const tenant = selectedTenant.value;
  if (!tenant || identityForm.userId < 1 || !identityForm.issuer.trim() || !identityForm.subject.trim()) {
    ElMessage.warning("请完整填写 OIDC 身份信息");
    return;
  }
  savingIdentity.value = true;
  try {
    await bindEnterpriseTenantIdentity(tenant.tenantKey, {
      userId: identityForm.userId,
      issuer: identityForm.issuer.trim(),
      subject: identityForm.subject.trim()
    });
    ElMessage.success("OIDC 身份已绑定");
  } catch (error) {
    ElMessage.error(getErrorMessage(error, "OIDC 身份绑定失败"));
  } finally {
    savingIdentity.value = false;
  }
};

onMounted(loadAll);
</script>
