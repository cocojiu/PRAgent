<template>
  <section class="dashboard-card codeowners-card" aria-label="代码所有者复核推荐">
    <h2>代码所有者复核推荐</h2>
    <p>按变更文件的风险、Finding 数和覆盖范围排序，最多推荐 3 位当前有效的租户成员。重命名展示新旧路径，文件数和 Finding 按变更去重。</p>
    <el-button :loading="loading && !accepting" :disabled="loading" @click="load">{{ result ? '刷新推荐' : '查看推荐' }}</el-button>
    <el-alert v-if="assigned" :title="`已分派给 ${assigned}`" type="success" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="result">
      <p role="status">{{ statusLabel }}</p>
      <template v-if="result.status === 'RECOMMENDATIONS'">
        <p>规则来源：{{ result.sourcePath }} · 基础提交 {{ result.baseSha?.slice(0, 12) }}</p>
        <ol class="codeowners-candidates">
          <li v-for="candidate in result.candidates" :key="candidate.userId">
            <strong>{{ candidate.username }}</strong>
            <el-button
              :loading="loading && accepting === candidate.userId"
              :disabled="loading"
              :aria-label="`接受推荐：${candidate.username}`"
              @click="accept(candidate.userId)"
            >接受推荐并分派</el-button>
            <span>覆盖 {{ candidate.coveredFiles }} 个文件，关联 {{ candidate.findingCount }} 条 Finding</span>
            <p>所有者标识：{{ candidate.externalIdentities.join('、') }}</p>
            <details><summary>查看覆盖文件</summary><ul><li v-for="path in candidate.paths" :key="path">{{ path }}</li></ul></details>
          </li>
        </ol>
        <el-alert title="推荐不会自动分派。选择“接受推荐并分派”将为当前批次设置接收人及 SLA。" type="info" :closable="false" />
      </template>
      <details v-if="result.basis.length"><summary>规则匹配依据（{{ result.basis.length }} 个路径）</summary>
        <ul><li v-for="row in result.basis" :key="row.path">
          <strong>{{ row.path }}</strong>：{{ row.pattern ? `第 ${row.line} 行 ${row.pattern}` : '没有匹配规则' }}
          <span>{{ row.owners.length ? row.owners.join('、') : '未声明所有者' }}</span>
          <p v-if="row.changedFiles.some(file => file !== row.path)">作为重命名前路径，关联变更：{{ row.changedFiles.filter(file => file !== row.path).join('、') }}</p>
        </li></ul>
      </details>
      <details v-if="result.uncoveredPaths.length" open><summary>候选人未覆盖的路径（{{ result.uncoveredPaths.length }}）</summary>
        <ul><li v-for="path in result.uncoveredPaths" :key="path">{{ path }}</li></ul>
      </details>
      <p v-if="result.unmappedIdentities.length">未映射到有效成员的标识：{{ result.unmappedIdentities.join('、') }}</p>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { fetchCodeownersRecommendations, acceptCodeownersRecommendation } from "@/api/codeowners";
import { activeTenant } from "@/stores/tenantContext";
import type { CodeownersRecommendations } from "@/types";

const props = defineProps<{ taskId: number; headSha: string }>();
const emit = defineEmits<{ assigned: [assignee: string] }>();
const result = ref<CodeownersRecommendations>();
const assigned = ref("");
const accepting = ref<number>();
const loading = ref(false);
const error = ref("");
let epoch = 0;
let disposed = false;
let request: AbortController | undefined;
const labels: Record<string, string> = {
  DISABLED: "推荐尚未启用，请继续人工分派。", INVALID_SCOPE: "当前租户上下文无效，请重新选择租户。",
  NOT_CONFIGURED: "尚未配置所有者成员映射。", SCOPE_NOT_CONFIGURED: "当前租户或仓库尚未配置所有者成员映射。",
  INVALID_MAPPING: "所有者成员映射无效，请联系仓库管理员。", INVALID_MAPPING_FILE: "所有者成员映射不可读取，请联系管理员。",
  AMBIGUOUS_MAPPING: "所有者成员映射存在歧义，请联系管理员。", UNAVAILABLE: "推荐暂时不可用，请稍后重试。",
  RECOMMENDATIONS: "已生成复核候选人。", UNASSIGNED: "当前没有可推荐的有效成员，请继续人工分派。",
  BUSY: "推荐查询繁忙，请稍后重试。", TASK_CHANGED: "任务批次已变化，请刷新任务后重新查询。",
  TASK_NOT_ELIGIBLE: "当前任务不处于待人工复核状态。", NO_CHANGED_FILES: "当前批次没有可用于推荐的变更文件。",
  GITHUB_NOT_CONFIGURED: "仓库 GitHub 连接尚未配置。", MISSING: "基础分支没有 CODEOWNERS，请继续人工分派。",
  BUDGET_EXCEEDED: "推荐范围超过处理上限，请继续人工分派。", MATCH_BUDGET_EXCEEDED: "规则匹配超过处理上限，请继续人工分派。",
  UNSUPPORTED_SYNTAX: "CODEOWNERS 含不支持的规则，请继续人工分派。", INVALID_PATH: "变更路径无法安全解析，请继续人工分派。",
  INVALID_CHANGES: "变更信息不完整，请刷新任务或继续人工分派。", RENAME_CONTEXT_UNAVAILABLE: "重命名前的路径信息不完整，请继续人工分派。"
};
const statusLabel = computed(() => labels[result.value?.status ?? ""] ?? "推荐状态无法识别，请刷新任务或继续人工分派。");
function clear() {
  epoch++; request?.abort(); request = undefined;
  result.value = undefined; loading.value = false; error.value = ""; assigned.value = ""; accepting.value = undefined;
}
async function load() {
  if (disposed || loading.value) return;
  clear();
  if (!Number.isSafeInteger(props.taskId) || props.taskId < 1 || !/^[a-f0-9]{40}$/i.test(props.headSha)) {
    error.value = "缺少当前任务或提交标识，请刷新任务。"; return;
  }
  const current = epoch;
  const taskId = props.taskId, headSha = props.headSha, tenant = activeTenant.value;
  const controller = new AbortController(); request = controller; loading.value = true;
  try {
    const response = await fetchCodeownersRecommendations(taskId, { signal: controller.signal });
    if (disposed || current !== epoch || activeTenant.value !== tenant) return;
    if (response.taskId !== taskId || (response.headSha && response.headSha.toLowerCase() !== headSha.toLowerCase())
        || (response.status === "RECOMMENDATIONS" && (!response.headSha || !response.baseSha || !response.attemptId))) {
      error.value = "推荐结果与当前提交不匹配，请刷新任务后重试。"; return;
    }
    result.value = response;
  } catch {
    if (!disposed && current === epoch) error.value = "推荐查询失败，请检查访问权限或稍后重试。";
  } finally { if (!disposed && current === epoch) { loading.value = false; request = undefined; } }
}
async function accept(userId: number) {
  const recommendation = result.value;
  if (disposed || loading.value || recommendation?.status !== "RECOMMENDATIONS" || !recommendation.attemptId
    || !recommendation.headSha || !recommendation.baseSha || !recommendation.candidates.some(candidate => candidate.userId === userId)) return;
  const current = epoch, taskId = props.taskId, tenant = activeTenant.value;
  const controller = new AbortController(); request = controller; loading.value = true; accepting.value = userId; error.value = "";
  try {
    const response = await acceptCodeownersRecommendation(taskId, { userId, attemptId: recommendation.attemptId,
      headSha: recommendation.headSha, baseSha: recommendation.baseSha }, { signal: controller.signal });
    if (disposed || current !== epoch || activeTenant.value !== tenant) return;
    if (response.taskId !== taskId || response.attemptId !== recommendation.attemptId
      || response.headSha.toLowerCase() !== recommendation.headSha.toLowerCase()) throw new Error("Version mismatch");
    clear(); assigned.value = response.assignee; emit("assigned", response.assignee);
  } catch {
    if (!disposed && current === epoch) {
      clear(); error.value = "分派结果未确认，请刷新任务核实；如推荐已变化，请重新查询。";
    }
  } finally { if (!disposed && current === epoch) { loading.value = false; request = undefined; accepting.value = undefined; } }
}
watch([() => props.taskId, () => props.headSha, activeTenant], clear, { flush: "sync" });
onBeforeUnmount(() => { disposed = true; clear(); });
</script>

<style scoped>
.codeowners-card p, .codeowners-card li { overflow-wrap: anywhere; }
.codeowners-candidates > li { margin: 12px 0; }
.codeowners-candidates span { display: block; margin-top: 4px; }
.codeowners-card details { margin: 12px 0; }
.codeowners-card details li { margin: 6px 0; }
</style>
