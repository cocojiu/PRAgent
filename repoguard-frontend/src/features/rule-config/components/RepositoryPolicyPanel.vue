<template>
  <section class="dashboard-card repository-policy-card">
    <div class="policy-governance-heading">
      <div>
        <h2>仓库策略即代码</h2>
        <p>预览默认分支与 PR 分支的 .repoguard.yml；平台安全下限始终优先。</p>
      </div>
      <el-button type="primary" :loading="loading" @click="loadPolicy">预览策略</el-button>
    </div>

    <el-form inline class="repository-policy-form" @submit.prevent="loadPolicy">
      <el-form-item label="组织">
        <el-input v-model="organization" placeholder="例如 octo-org" clearable />
      </el-form-item>
      <el-form-item label="仓库">
        <el-input v-model="repository" placeholder="例如 repo-guard" clearable />
      </el-form-item>
      <el-form-item label="PR 提交 SHA">
        <el-input v-model="headSha" placeholder="可选，默认当前预览" clearable />
      </el-form-item>
    </el-form>

    <el-alert v-if="errorMessage" type="error" :title="errorMessage" show-icon :closable="false" />
    <template v-if="preview">
      <div class="repository-policy-summary">
        <el-tag :type="preview.checkMode === 'BLOCKING' ? 'danger' : 'warning'">
          Check：{{ preview.checkMode }}
        </el-tag>
        <el-tag>Comment：{{ preview.commentMode }}</el-tag>
        <span>LLM：{{ preview.effectiveLlmEnabled === false ? "关闭" : "开启" }}</span>
        <span v-if="preview.effectiveTokenBudget">Token：{{ preview.effectiveTokenBudget.toLocaleString() }}</span>
        <span v-if="preview.effectiveCostBudget">成本上限：{{ preview.effectiveCostBudget }}</span>
      </div>
      <el-alert
        v-if="preview.warnings.length"
        type="warning"
        :title="preview.warnings.join('；')"
        show-icon
        :closable="false"
      />
      <el-table :data="ruleRows" class="rg-table" size="small" aria-label="仓库策略规则预览">
        <el-table-column prop="ruleId" label="规则" min-width="150" />
        <el-table-column label="有效状态" min-width="120">
          <template #default="{ row }">
            <el-tag :type="row.effectiveEnabled ? 'success' : 'info'">
              {{ row.effectiveEnabled ? "启用" : "停用" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="effectiveSeverity" label="严重级别" min-width="110" />
        <el-table-column prop="effectiveEnforcement" label="处置模式" min-width="110" />
        <el-table-column prop="conflict" label="冲突说明" min-width="220" show-overflow-tooltip />
      </el-table>
    </template>

    <div class="repository-suppression-heading">
      <div>
        <h3>误报抑制提案</h3>
        <p>提案默认不生效；激活前会回放近期命中并记录审计。</p>
      </div>
      <el-button text :disabled="!organization || !repository" @click="loadSuppressions">刷新</el-button>
    </div>
    <el-form inline class="repository-policy-form" @submit.prevent="createSuppression">
      <el-form-item label="规则 ID"><el-input v-model="suppression.ruleId" placeholder="RG-JAVA-001" /></el-form-item>
      <el-form-item label="文件 Glob"><el-input v-model="suppression.fileGlob" placeholder="src/**/*.java" /></el-form-item>
      <el-form-item label="原因"><el-input v-model="suppression.reason" placeholder="说明误报边界" /></el-form-item>
      <el-form-item label="到期时间"><el-date-picker v-model="suppression.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
      <el-form-item><el-button type="primary" plain :disabled="!canManage" :loading="saving" @click="createSuppression">提交提案</el-button></el-form-item>
    </el-form>
    <el-table :data="suppressions" class="rg-table" size="small" aria-label="仓库误报抑制列表">
      <el-table-column prop="ruleId" label="规则" min-width="140" />
      <el-table-column prop="fileGlob" label="文件 Glob" min-width="180" show-overflow-tooltip />
      <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" min-width="100" />
      <el-table-column prop="expiresAt" label="到期" min-width="170" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PROPOSED'" size="small" type="primary" plain :disabled="!canManage" @click="activate(row.id)">激活</el-button>
          <el-button v-if="row.status === 'ACTIVE'" size="small" type="danger" plain :disabled="!canManage" @click="revoke(row.id)">撤销</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无抑制提案" /></template>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  activateRepositorySuppression,
  createRepositorySuppression,
  fetchRepositoryPolicyPreview,
  fetchRepositorySuppressions,
  revokeRepositorySuppression
} from "@/api/config";
import { canManage } from "@/stores/authState";
import type {
  RepositoryPolicyPreviewResponse,
  RepositorySuppressionResponse
} from "@/types";

const organization = ref("");
const repository = ref("");
const headSha = ref("");
const preview = ref<RepositoryPolicyPreviewResponse>();
const suppressions = ref<RepositorySuppressionResponse[]>([]);
const loading = ref(false);
const saving = ref(false);
const errorMessage = ref("");
const suppression = reactive({
  ruleId: "",
  fileGlob: "",
  symbol: "",
  reason: "",
  expiresAt: new Date(Date.now() + 90 * 24 * 60 * 60 * 1000).toISOString().slice(0, 19)
});

const ruleRows = computed(() => preview.value ? Object.values(preview.value.rules) : []);

const loadSuppressions = async () => {
  if (!organization.value || !repository.value) return;
  suppressions.value = await fetchRepositorySuppressions(organization.value, repository.value);
};

const loadPolicy = async () => {
  if (!organization.value || !repository.value) {
    errorMessage.value = "请先填写组织和仓库";
    return;
  }
  loading.value = true;
  errorMessage.value = "";
  try {
    const [policy] = await Promise.all([
      fetchRepositoryPolicyPreview(organization.value, repository.value, headSha.value || undefined),
      loadSuppressions()
    ]);
    preview.value = policy;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "策略预览失败";
  } finally {
    loading.value = false;
  }
};

const createSuppression = async () => {
  if (!canManage.value || !organization.value || !repository.value) return;
  if (!suppression.ruleId || !suppression.reason || !suppression.expiresAt) {
    ElMessage.warning("请填写规则、原因和到期时间");
    return;
  }
  saving.value = true;
  try {
    await createRepositorySuppression({
      organization: organization.value,
      repository: repository.value,
      ruleId: suppression.ruleId,
      fileGlob: suppression.fileGlob || undefined,
      symbol: suppression.symbol || undefined,
      reason: suppression.reason,
      expiresAt: suppression.expiresAt
    });
    ElMessage.success("抑制提案已提交");
    await loadSuppressions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交提案失败");
  } finally {
    saving.value = false;
  }
};

const activate = async (id: number) => {
  try {
    await activateRepositorySuppression(id, "仓库策略审查后激活");
    await loadSuppressions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "激活失败");
  }
};

const revoke = async (id: number) => {
  try {
    await revokeRepositorySuppression(id, "仓库策略审查后撤销");
    await loadSuppressions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "撤销失败");
  }
};
</script>
