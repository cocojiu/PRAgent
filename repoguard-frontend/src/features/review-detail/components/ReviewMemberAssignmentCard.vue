<template>
  <section class="dashboard-card member-assignment-card" aria-label="人工选择复核成员">
    <h2>人工选择复核成员</h2>
    <p>可选择当前租户中具有复核权限的有效成员。确认分派会设置接收人及 SLA。</p>
    <form @submit.prevent="load">
      <label>用户名开头 <input v-model="search" maxlength="128" :disabled="loading" autocomplete="off" /></label>
      <el-button native-type="submit" :loading="loading && !confirming" :disabled="loading">{{ result ? '刷新成员' : '查看成员' }}</el-button>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="assigned" :title="`已分派给 ${assigned}`" type="success" :closable="false" />
    <template v-if="result">
      <p v-if="!result.members.length" role="status">没有匹配的有效复核成员，请调整用户名后查询。</p>
      <fieldset v-else :disabled="loading">
        <legend>选择复核成员</legend>
        <label v-for="member in result.members" :key="member.userId">
          <input v-model="selected" type="radio" :value="member.userId" name="review-member" /> {{ member.username }}
        </label>
      </fieldset>
      <p v-if="result.hasMore">仅显示前 20 位成员，请输入更完整的用户名开头缩小范围。</p>
      <el-button v-if="result.members.length" :loading="confirming" :disabled="loading || !selected" @click="confirm">确认分派</el-button>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { fetchReviewAssignmentOptions, confirmReviewMemberAssignment } from "@/api/reviewAssignment";
import { activeTenant } from "@/stores/tenantContext";
import type { ReviewAssignmentOptions } from "@/types";

const props = defineProps<{ taskId: number; headSha: string }>();
const emit = defineEmits<{ assigned: [assignee: string] }>();
const search = ref("");
const selected = ref<number>();
const result = ref<ReviewAssignmentOptions>();
const loading = ref(false), confirming = ref(false);
const error = ref(""), assigned = ref("");
let epoch = 0, disposed = false;
let request: AbortController | undefined;
function clear() {
  epoch++; request?.abort(); request = undefined;
  result.value = undefined; selected.value = undefined;
  loading.value = false; confirming.value = false; error.value = ""; assigned.value = "";
}
async function load() {
  if (disposed || loading.value) return;
  clear();
  if (!Number.isSafeInteger(props.taskId) || props.taskId < 1 || !/^[a-f0-9]{40}$/i.test(props.headSha)) {
    error.value = "缺少当前任务或提交标识，请刷新任务。"; return;
  }
  const current = epoch, tenant = activeTenant.value, taskId = props.taskId, headSha = props.headSha;
  const controller = new AbortController(); request = controller; loading.value = true;
  try {
    const response = await fetchReviewAssignmentOptions(taskId, search.value.trim() || undefined, { signal: controller.signal });
    if (disposed || current !== epoch || tenant !== activeTenant.value) return;
    if (response.taskId !== taskId || response.headSha.toLowerCase() !== headSha.toLowerCase()) throw new Error("Version mismatch");
    result.value = response;
  } catch {
    if (!disposed && current === epoch) error.value = "成员查询失败，请刷新任务并检查访问权限后重试。";
  } finally { if (!disposed && current === epoch) { loading.value = false; request = undefined; } }
}
async function confirm() {
  const options = result.value;
  if (disposed || loading.value || !options || !selected.value || !options.members.some(member => member.userId === selected.value)) return;
  const current = epoch, tenant = activeTenant.value, taskId = props.taskId;
  const controller = new AbortController(); request = controller; loading.value = true; confirming.value = true; error.value = "";
  try {
    const response = await confirmReviewMemberAssignment(taskId, { userId: selected.value, attemptId: options.attemptId,
      headSha: options.headSha, assignmentVersion: options.assignmentVersion }, { signal: controller.signal });
    if (disposed || current !== epoch || tenant !== activeTenant.value) return;
    if (response.taskId !== taskId || response.attemptId !== options.attemptId
      || response.headSha.toLowerCase() !== options.headSha.toLowerCase()) throw new Error("Version mismatch");
    clear(); assigned.value = response.assignee; emit("assigned", response.assignee);
  } catch {
    if (!disposed && current === epoch) { clear(); error.value = "分派结果未确认，请刷新任务核实，并重新查询成员。"; }
  } finally { if (!disposed && current === epoch) { loading.value = false; confirming.value = false; request = undefined; } }
}
watch([() => props.taskId, () => props.headSha, activeTenant], () => { clear(); search.value = ""; }, { flush: "sync" });
watch(search, () => { if (!loading.value) clear(); }, { flush: "sync" });
onBeforeUnmount(() => { disposed = true; clear(); });
</script>

<style scoped>
.member-assignment-card p, .member-assignment-card label { overflow-wrap: anywhere; }
.member-assignment-card form { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.member-assignment-card fieldset { margin: 12px 0; }
.member-assignment-card fieldset label { display: block; margin: 6px 0; }
.member-assignment-card input[type="text"], .member-assignment-card input:not([type]) { max-width: 100%; padding: 6px; }
</style>
