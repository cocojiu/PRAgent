<template>
  <article class="notification-settings-card">
    <h2>触发策略</h2>
    <div class="switch-row">
      <span>GitHub 评论通知</span>
      <el-switch v-model="form.githubComment" :disabled="!canManage" @change="emit('save')" />
    </div>
    <div class="switch-row">
      <span>失败任务通知</span>
      <el-switch v-model="form.failedTask" :disabled="!canManage" @change="emit('save')" />
    </div>
    <div class="switch-row">
      <span>高风险 PR 通知</span>
      <el-switch v-model="form.highRiskPr" :disabled="!canManage" @change="emit('save')" />
    </div>
    <p class="settings-help-text">审查完成、人工复核、评论回写等细粒度事件可在渠道绑定中按仓库配置。</p>

    <h2 class="settings-section-title">默认通知范围</h2>
    <el-form label-position="top">
      <el-form-item label="接收对象">
        <el-select v-model="recipientTarget" :disabled="!canManage">
          <el-option label="项目成员" value="members" />
          <el-option label="仓库管理员" value="maintainers" />
        </el-select>
      </el-form-item>
      <el-form-item label="接收组">
        <el-select v-model="recipientGroup" :disabled="!canManage">
          <el-option label="运维组" value="ops" />
          <el-option label="研发组" value="dev" />
        </el-select>
      </el-form-item>
      <el-form-item label="免打扰时段">
        <el-select v-model="quietHours" :disabled="!canManage">
          <el-option label="不启用" value="off" />
          <el-option label="22:00 - 08:00" value="night" />
        </el-select>
      </el-form-item>
    </el-form>

    <h2 class="settings-section-title">失败重试</h2>
    <el-form label-position="top">
      <el-form-item label="最大重试次数">
        <el-input-number v-model="maxRetryCount" :min="1" :max="10" :disabled="!canManage" />
        <span class="form-tail">次</span>
      </el-form-item>
      <el-form-item label="重试间隔">
        <el-select v-model="retryInterval" :disabled="!canManage">
          <el-option label="5 分钟" value="5" />
          <el-option label="15 分钟" value="15" />
          <el-option label="30 分钟" value="30" />
          <el-option label="60 分钟" value="60" />
        </el-select>
      </el-form-item>
    </el-form>
    <div class="retry-chips">
      <button
        v-for="item in retryIntervals"
        :key="item"
        type="button"
        :class="{ active: retryInterval === item }"
        @click="retryInterval = item"
      >
        {{ item }} 分钟
      </button>
    </div>
  </article>
</template>

<script setup lang="ts">
import { ref } from "vue";
import type { NotificationSettings } from "@/types";

defineProps<{
  canManage: boolean;
  form: NotificationSettings;
}>();

const emit = defineEmits<{
  save: [];
}>();

const recipientTarget = ref("members");
const recipientGroup = ref("ops");
const quietHours = ref("off");
const maxRetryCount = ref(5);
const retryInterval = ref("5");
const retryIntervals = ["1", "5", "15", "30", "60"];
</script>

<style scoped>
.notification-settings-card {
  padding: 20px 22px;
  border: 1px solid #e5eaf3;
  border-radius: 8px;
  background: #ffffff;
}

.notification-settings-card h2 {
  margin: 0;
  color: #0f172a;
  font-size: 20px;
}

.settings-section-title {
  margin-top: 22px !important;
  padding-top: 18px;
  border-top: 1px solid #eef2f7;
}

.settings-help-text {
  margin: 12px 0 0;
  color: #64748b;
  line-height: 1.7;
}

.notification-settings-card :deep(.el-select),
.notification-settings-card :deep(.el-input-number) {
  width: 100%;
}

.notification-settings-card :deep(.el-form-item) {
  margin-bottom: 12px;
}

.form-tail {
  margin-left: 8px;
  color: #64748b;
}

.retry-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.retry-chips button {
  height: 34px;
  padding: 0 12px;
  border: 1px solid #d9e2ec;
  border-radius: 6px;
  background: #ffffff;
  color: #475569;
  cursor: pointer;
}

.retry-chips button.active {
  border-color: #1268ff;
  color: #1268ff;
  background: #eaf2ff;
}
</style>
