<template>
  <article class="dashboard-card">
    <div class="card-title-row"><h2>待核验的 GitHub 回写</h2>
      <el-button :loading="busy" @click="load()">{{ loaded ? "刷新待核验记录" : "加载待核验记录" }}</el-button>
    </div>
    <p>这些请求可能已成功，系统不会再次发布。核对 GitHub 中的评论或 Review 后，填写其数字 ID；核验只读取远端。</p>
    <el-alert v-if="error" type="warning" :title="error" :closable="false" />
    <p v-if="loaded && rows.length === 0">当前没有待核验记录。</p>
    <section v-for="row in rows" :key="row.id" class="resolution-row">
      <p>{{ row.findingId ? `发现 #${row.findingId}` : "PR 总评" }} · 回写记录 #{{ row.id }}</p>
      <p>{{ row.message }}</p>
      <el-alert v-if="!row.verifiable" type="info" title="历史记录缺少核验标记，请保持待核验，或明确关闭且不再回写。" :closable="false" />
      <div class="resolution-actions">
        <el-select v-model="row.kind" aria-label="GitHub 记录类型" :disabled="busy || !row.verifiable">
          <el-option label="PR 评论" value="issue_comment" />
          <el-option label="行评论" value="review_comment" />
          <el-option label="Review" value="review" />
        </el-select>
        <el-input v-model="row.remoteId" placeholder="GitHub 评论或 Review 的数字 ID" aria-label="GitHub 数字 ID" :disabled="busy || !row.verifiable" />
        <el-button type="primary" :disabled="busy || !row.verifiable || !validId(row.remoteId)" @click="verify(row)">只读核验并确认已发布</el-button>
        <el-button :disabled="busy" @click="close(row)">关闭且不再回写</el-button>
      </div>
    </section>
    <el-button v-if="rows.length === 20" :disabled="busy" @click="load(rows[rows.length - 1]!.id)">下一页</el-button>
  </article>
</template>
<script setup lang="ts">
import { onScopeDispose, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { fetchPendingGithubPublications, verifyGithubPublication, closeGithubPublication } from "@/api/githubPublications";
import type { GithubReferenceKind, PendingGithubPublication } from "@/api/githubPublicationTypes";
import { activeTenant } from "@/stores/tenantContext";
import { getErrorMessage } from "@/utils/errors";
const props = defineProps<{ taskId: number }>();
const emit = defineEmits<{ resolved: [] }>();
type Row = PendingGithubPublication & { kind: GithubReferenceKind; remoteId: string };
const rows = ref<Row[]>([]);
const loaded = ref(false);
const busy = ref(false);
const error = ref("");
let revision = 0;
let disposed = false;
let controller: AbortController | undefined;
const validId = (value: string) => /^[1-9][0-9]*$/.test(value) && Number.isSafeInteger(Number(value));
const reset = () => { revision++; controller?.abort(); rows.value = []; loaded.value = false; busy.value = false; error.value = ""; };
watch([() => props.taskId, activeTenant], reset);
onScopeDispose(() => { disposed = true; reset(); });
const begin = () => { controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ""; return { id: ++revision, task: props.taskId, signal: controller.signal }; };
const current = (id: number) => !disposed && id === revision;
async function load(afterId = 0) {
  const call = begin();
  try {
    const result = await fetchPendingGithubPublications(call.task, afterId, { signal: call.signal });
    if (!current(call.id)) return;
    rows.value = result.map(row => ({ ...row, kind: row.targetType === "pull_request" ? "issue_comment" : "review_comment", remoteId: "" }));
    loaded.value = true;
  } catch (failure) { if (current(call.id)) error.value = getErrorMessage(failure); }
  finally { if (current(call.id)) busy.value = false; }
}
async function verify(row: Row) {
  if (busy.value || !row.verifiable || !validId(row.remoteId)) return;
  const call = begin();
  try {
    await verifyGithubPublication(call.task, row.id, { version: row.version, kind: row.kind, remoteId: Number(row.remoteId) }, { signal: call.signal });
    if (current(call.id)) { await load(); if (!disposed && props.taskId === call.task) emit("resolved"); }
  } catch (failure) { if (current(call.id)) error.value = getErrorMessage(failure); }
  finally { if (current(call.id)) busy.value = false; }
}
async function close(row: Row) {
  if (busy.value) return;
  const original = revision;
  try {
    const answer = await ElMessageBox.prompt("关闭不会撤回已发出的请求，也不会允许重新发布。请填写关闭原因（至少5字）。", "关闭且不再回写", {
      confirmButtonText: "确认关闭，不再回写", cancelButtonText: "保留待核验", inputValidator: value => value.trim().length >= 5 || "请填写至少5字原因"
    });
    if (disposed || revision !== original) return;
    const call = begin();
    try {
      await closeGithubPublication(call.task, row.id, { version: row.version, reason: answer.value.trim(), confirmNoRetry: true }, { signal: call.signal });
      if (current(call.id)) { await load(); if (!disposed && props.taskId === call.task) emit("resolved"); }
    } catch (failure) { if (current(call.id)) error.value = getErrorMessage(failure); }
    finally { if (current(call.id)) busy.value = false; }
  } catch { /* User cancelled; keep the durable unknown state. */ }
}
</script>
<style scoped>
.resolution-row { border-top: 1px solid var(--el-border-color); margin-top: 16px; padding-top: 12px; }
.resolution-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.resolution-actions .el-select { width: 140px; }
.resolution-actions .el-input { width: min(320px, 100%); }
</style>
