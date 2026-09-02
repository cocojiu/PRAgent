<template>
  <el-card class="github-checks-wizard" shadow="never">
    <template #header>
      <div class="github-checks-wizard__header">
        <div>
          <h2>GitHub App / Checks 设置向导</h2>
          <p>PAT 仍是个人模式首次审查的最短路径；Checks 是确认后启用的可选增强。</p>
        </div>
        <el-tag :type="status?.ready ? 'success' : 'warning'">
          {{ status?.ready ? "已具备启用条件" : "需要自检" }}
        </el-tag>
      </div>
    </template>

    <el-alert v-if="errorMessage" type="error" :closable="false" show-icon class="github-checks-wizard__alert">
      {{ errorMessage }}
    </el-alert>

    <div class="github-checks-wizard__target">
      <el-input v-model="organization" placeholder="组织，例如 cocojiu" aria-label="GitHub 组织" />
      <el-input v-model="repository" placeholder="仓库，例如 PRAgent" aria-label="GitHub 仓库" />
      <el-button type="primary" plain :loading="loading" :disabled="!canManage" @click="load">
        运行权限自检
      </el-button>
    </div>

    <div v-if="status" class="github-checks-wizard__body">
      <div class="github-checks-wizard__summary">
        <span>installation：{{ status.installationId ?? "未绑定" }}</span>
        <span>仓库 Check：{{ status.repositoryCheckRunEnabled ? "已确认启用" : "未确认" }}</span>
        <span>有效状态：{{ status.effectiveCheckRunEnabled ? "运行中" : "关闭" }}</span>
      </div>

      <div class="github-checks-wizard__diagnostics">
        <div
          v-for="item in status.diagnostics"
          :key="item.code"
          :class="['github-checks-diagnostic', item.status]"
        >
          <div>
            <strong>{{ item.label }}</strong>
            <span>{{ item.message }}</span>
          </div>
          <el-tag size="small" :type="item.blocking ? 'danger' : item.status === 'success' ? 'success' : 'info'">
            {{ item.blocking ? "阻断" : "提示" }}
          </el-tag>
        </div>
      </div>

      <div class="github-checks-wizard__webhook">
        <span>Webhook URL：{{ status.webhook.endpointUrl }}</span>
        <span>签名：{{ status.webhook.signatureRequired && status.webhook.secretConfigured ? "已保护" : "需修复" }}</span>
        <span>最近 delivery：{{ status.webhook.lastDeliveryId ?? "未观测" }} / {{ status.webhook.lastDeliveryStatus }}</span>
      </div>

      <div class="github-checks-wizard__preview">
        <div class="github-checks-wizard__preview-actions">
          <el-input-number v-model="pullRequestNumber" :min="1" placeholder="测试 PR" aria-label="测试 PR 编号" />
          <el-button type="primary" plain :loading="previewing" :disabled="!canManage || !pullRequestNumber" @click="preview">
            创建 neutral 预览 Check
          </el-button>
        </div>
        <div class="github-checks-wizard__preview-grid">
          <span>desired/applied：{{ status.preview.desiredVersion }} / {{ status.preview.appliedVersion }}</span>
          <span>阶段：{{ status.preview.desiredStage }} / {{ status.preview.appliedStage ?? "未应用" }}</span>
          <span>重试：{{ status.preview.retryAttempts }}</span>
          <span>annotations：{{ status.preview.annotationCount }}{{ status.preview.annotationTruncated ? "（已截断）" : "" }}</span>
          <span>conclusion：{{ status.preview.conclusion ?? "未执行" }}</span>
          <span>{{ status.preview.message }}</span>
        </div>
      </div>

      <div class="github-checks-wizard__actions">
        <el-button
          type="success"
          :loading="saving"
          :disabled="!canManage || !status.ready || status.repositoryCheckRunEnabled"
          @click="confirmEnable"
        >
          确认启用本仓库 Check
        </el-button>
        <el-button
          type="danger"
          plain
          :loading="saving"
          :disabled="!canManage || !status.repositoryCheckRunEnabled"
          @click="confirmDisable"
        >
          停用 RepoGuard Check
        </el-button>
      </div>
      <p class="github-checks-wizard__guidance">{{ status.mergeGateGuidance }}</p>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useGithubChecksSetup } from "../composables/useGithubChecksSetup";

const props = defineProps<{
  canManage: boolean;
  initialOrganization?: string;
  initialRepository?: string;
}>();

const canManage = computed(() => props.canManage);

const {
  organization,
  repository,
  pullRequestNumber,
  status,
  loading,
  previewing,
  saving,
  errorMessage,
  load,
  preview,
  setEnabled
} = useGithubChecksSetup({ canManage });

watch(
  () => [props.initialOrganization, props.initialRepository],
  ([nextOrganization, nextRepository]) => {
    if (!organization.value && nextOrganization) organization.value = nextOrganization;
    if (!repository.value && nextRepository) repository.value = nextRepository;
  },
  { immediate: true }
);

const confirmEnable = async () => {
  await ElMessageBox.confirm(
    "仅会启用该仓库后续的 RepoGuard Check Run，不会修改 GitHub branch protection；是否继续？",
    "确认启用",
    { type: "warning", confirmButtonText: "确认启用", cancelButtonText: "取消" }
  );
  await setEnabled(true);
  if (!errorMessage.value) ElMessage.success("已启用该仓库的 RepoGuard Check Run");
};

const confirmDisable = async () => {
  await ElMessageBox.confirm(
    "停用只影响后续 Check Run，历史任务和 GitHub 历史状态会保留；是否继续？",
    "确认停用",
    { type: "warning", confirmButtonText: "确认停用", cancelButtonText: "取消" }
  );
  await setEnabled(false);
  if (!errorMessage.value) ElMessage.success("已停用该仓库的 RepoGuard Check Run");
};
</script>
