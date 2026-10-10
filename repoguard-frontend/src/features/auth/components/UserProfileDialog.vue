<template>
  <el-dialog v-model="visibleModel" title="个人资料" width="480px" append-to-body destroy-on-close>
    <p>账户资料由服务端维护。</p>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon />
    <p v-if="loading" role="status">正在读取个人资料...</p>
    <p v-else-if="profile && !confirmed">以下为上次读取的资料，当前状态尚未确认，请重试刷新。</p>
    <dl v-if="profile" class="profile-details">
      <dt>用户名</dt><dd>{{ profile.username }}</dd>
      <dt>邮箱</dt><dd>{{ profile.email }}</dd>
      <dt>角色</dt><dd>{{ roleLabel }}</dd>
      <dt>账户状态</dt><dd>{{ profile.status === 'ACTIVE' ? '正常' : profile.status === 'DISABLED' ? '已停用' : profile.status }}</dd>
      <dt>语言</dt><dd>{{ profile.language || '未设置' }}</dd>
      <dt>时区</dt><dd>{{ profile.timezone || '未设置' }}</dd>
      <dt>最近登录</dt><dd>{{ formatDateTime(profile.lastLoginAt) }}</dd>
    </dl>
    <template #footer>
      <el-button :loading="loading" @click="refreshProfile">刷新资料</el-button>
      <el-button @click="visibleModel = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { getCurrentUser, type CurrentUser } from "@/api/auth";
import { hasAuthToken } from "@/api/client";
import { currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";
import { formatDateTime } from "@/utils/dateTime";
import { getErrorMessage } from "@/utils/errors";

const visibleModel = defineModel<boolean>({ required: true });
const profile = ref<CurrentUser>(); const confirmed = ref(false); const loading = ref(false); const errorMessage = ref("");
let disposed = false; let revision = 0; let controller: AbortController | undefined;
const roles: Record<string, string> = { ADMIN: "管理员", PLATFORM_ADMIN: "平台管理员", TENANT_ADMIN: "租户管理员", RULE_ADMIN: "规则管理员", REVIEWER: "审查员", VIEWER: "只读用户" };
const roleLabel = computed(() => roles[profile.value?.role ?? ""] ?? profile.value?.role ?? "未设置");
const clear = () => {
  revision += 1; controller?.abort(); controller = undefined; loading.value = false; errorMessage.value = "";
  profile.value = undefined; confirmed.value = false;
};
const refreshProfile = async () => {
  if (disposed || !visibleModel.value) return;
  const id = currentUser.value?.id;
  if (!hasAuthToken() || !id || !Number.isSafeInteger(id)) { errorMessage.value = "当前账户资料尚未载入，请重新登录后重试。"; return; }
  controller?.abort(); const request = new AbortController(); controller = request; const version = revision;
  const current = () => !disposed && visibleModel.value && hasAuthToken() && version === revision
    && currentUser.value?.id === id && controller === request && !request.signal.aborted;
  loading.value = true; confirmed.value = false; errorMessage.value = "";
  try {
    const user = await getCurrentUser({ signal: request.signal });
    if (!current()) return;
    if (user.id !== id) { errorMessage.value = "返回资料与当前账户不一致，请重新登录后确认。"; return; }
    profile.value = { ...user }; confirmed.value = true;
  } catch (error) {
    if (current()) errorMessage.value = getErrorMessage(error, "个人资料读取失败，请重试刷新。");
  } finally { if (controller === request) { controller = undefined; loading.value = false; } }
};
watch(visibleModel, visible => {
  clear();
  if (visible) {
    if (currentUser.value) profile.value = { ...currentUser.value };
    void refreshProfile();
  }
}, { immediate: true, flush: "sync" });
watch([() => currentUser.value?.id, () => currentUser.value?.role, () => currentUser.value?.status, activeTenant], () => {
  clear(); visibleModel.value = false;
}, { flush: "sync" });
onBeforeUnmount(() => { disposed = true; clear(); });
</script>

<style scoped>
.profile-details { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 12px; margin: 20px 0; }
.profile-details dt { color: var(--el-text-color-secondary); }
.profile-details dd { margin: 0; overflow-wrap: anywhere; }
</style>
