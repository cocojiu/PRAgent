<template>
  <section class="dashboard-card sarif-entry">
    <div><h2>接入 CI 扫描结果</h2><p>将 CodeQL 或 Semgrep 的现有 SARIF 报告关联到本次审查。</p></div>
    <el-button plain @click="open = true">SARIF 接入向导</el-button>
    <el-dialog v-model="open" title="CI SARIF 接入" width="min(900px, 94vw)" top="5vh" append-to-body destroy-on-close>
      <div v-loading="loading" class="sarif-guide">
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-button :loading="loading" @click="load">刷新任务与上传记录</el-button>
        <template v-if="setup">
          <h3>1. 确认上传目标</h3>
          <dl class="sarif-target">
            <dt>仓库 / PR</dt><dd>{{ setup.organization }}/{{ setup.repository }} · #{{ setup.prNumber ?? '—' }}</dd>
            <dt>任务 / 审查批次</dt><dd>#{{ setup.taskId }} / {{ setup.attemptId ?? '暂无' }}</dd>
            <dt>绑定 commit</dt><dd><code>{{ setup.commitSha ?? '暂无' }}</code></dd>
            <dt>支持格式</dt><dd>SARIF {{ setup.sarifVersion }}；JSON 最多 {{ setup.maxSarifBytes.toLocaleString() }} 字节，ZIP 最多 {{ setup.maxUploadBytes.toLocaleString() }} 字节。</dd>
          </dl>
          <p>以下片段上传单个扫描器的 JSON 报告。CI 必须检出上面的 PR head commit，不使用平台生成的合并 commit。</p>
          <el-alert v-if="!setup.attemptId" title="任务还没有当前审查批次，暂不能生成上传凭证。" type="info" :closable="false" />

          <h3>2. 准备报告与上传片段</h3>
          <el-form label-position="top">
            <div class="sarif-options">
              <el-form-item label="扫描器"><el-select v-model="scanner" aria-label="扫描器"><el-option label="CodeQL" value="codeql" /><el-option label="Semgrep" value="semgrep" /></el-select></el-form-item>
              <el-form-item label="运行环境"><el-select v-model="platform" aria-label="运行环境"><el-option label="GitHub Actions" value="github" /><el-option label="GitLab CI" value="gitlab" /><el-option label="通用 Shell（Python 3）" value="shell" /></el-select></el-form-item>
            </div>
            <el-form-item label="RepoGuard 站点地址"><el-input v-model="baseUrl" placeholder="https://pragent.example.com" /></el-form-item>
          </el-form>
          <p>先在现有 CI 中生成 <code>{{ scanner }}.sarif</code>，再执行上传片段。需要 Python 3、Git 及网络访问。重试同一份报告时保持 scan ID 不变。</p>
          <p v-if="scanner === 'codeql'">CodeQL 使用 <code>--format=sarifv2.1.0 --output=codeql.sarif</code>；沿用已有查询集和扫描任务。</p>
          <p v-else>Semgrep 在已有扫描命令中增加 <code>--sarif --output=semgrep.sarif</code>；沿用已有规则配置。</p>
          <el-alert v-if="snippetError" :title="snippetError" type="warning" :closable="false" />
          <template v-else><pre class="sarif-snippet" tabindex="0" aria-label="不含凭据的 CI 上传片段">{{ snippet }}</pre><el-button :disabled="!snippet" @click="copySnippet">复制上传片段</el-button></template>

          <h3>3. 在上传前获取短期凭证</h3>
          <p>有效期 {{ Math.floor(setup.credentialTtlSeconds / 60) }} 分钟。请在报告生成后、上传前获取，将其注入 CI secret <code>REPOGUARD_CI_CREDENTIAL</code>。上传结束后删除该临时 secret；本向导不会写入 CI 配置。</p>
          <p>首次验收可下载报告，在对应 commit 下运行通用 Shell 片段；CI 方式请单独启动上传作业并读取已有报告。长时间扫描请先完成再获取凭证，不能将此短期凭证用于长期定时运行。</p>
          <div class="sarif-actions">
            <el-button type="primary" :disabled="!setup.attemptId || loading" :loading="issuing" @click="issue">生成短期上传凭证</el-button>
            <el-button :disabled="!credential || now >= expiresAt" @click="copyCredential">复制凭证</el-button>
          </div>
          <p v-if="expiresAt" role="status">{{ now >= expiresAt ? '凭证已过期，请重新生成。' : `到期时间：${formatDateTime(new Date(expiresAt).toISOString())}` }}{{ copiedCredential ? ' 已复制；页面中的凭证已清除。' : '' }}</p>
          <p class="sarif-muted">凭证不会显示在片段中或保存到浏览器存储；关闭向导、切换任务或刷新绑定信息会清除页面中的凭证。</p>

          <h3>最近上传记录</h3>
          <p class="sarif-muted">当前 attempt 和 commit 最近 20 条成功上传记录。失败请求不会形成成功批次，请根据 CI 响应排查。</p>
          <el-table v-if="setup.recentUploads.length" :data="setup.recentUploads" size="small">
            <el-table-column prop="toolName" label="扫描器" min-width="130" />
            <el-table-column prop="toolVersion" label="版本" width="90" />
            <el-table-column prop="scanRunId" label="Scan ID" min-width="150" />
            <el-table-column label="批次状态" width="100"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : row.status === 'SUPERSEDED' ? '已被替换' : row.status }}</template></el-table-column>
            <el-table-column prop="imported" label="导入" width="65" /><el-table-column prop="skipped" label="跳过" width="65" />
            <el-table-column label="扫描完成时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.completedAt) }}</template></el-table-column>
          </el-table>
          <el-empty v-else description="当前审查批次还没有 CI 上传记录" :image-size="65" />
          <details><summary>上传失败时如何处理</summary><ul>
            <li>401 / 403：确认临时凭证未过期，并与当前任务、租户匹配。</li>
            <li>400 / 409：刷新任务绑定，核对 attempt、commit、扫描器名称/版本和 SARIF 格式。名称和版本由片段从报告读取。</li>
            <li>413：缩小报告；ZIP 解压后的 JSON 仍受 JSON 上限约束。</li>
            <li>网络中断：在凭证有效期内使用相同报告和 scan ID 重试；已导入的相同批次会幂等返回。</li>
          </ul></details>
        </template>
      </div>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { fetchCiSarifSetup, issueCiSarifCredential, type CiSarifSetup } from "@/api/ciSarif";
import { buildCiSarifSnippet, type SarifCiPlatform, type SarifScanner } from "@/features/review-detail/ciSarifSnippet";
import { formatDateTime } from "@/utils/dateTime";

const props = defineProps<{ taskId: number }>();
const open = ref(false);
const loading = ref(false);
const issuing = ref(false);
const error = ref("");
const setup = ref<CiSarifSetup>();
const scanner = ref<SarifScanner>("codeql");
const platform = ref<SarifCiPlatform>("github");
const baseUrl = ref(window.location.origin);
const credential = ref("");
const expiresAt = ref(0);
const copiedCredential = ref(false);
const now = ref(Date.now());
let epoch = 0;
let controller: AbortController | undefined;
let timer: ReturnType<typeof setInterval> | undefined;
const rendered = computed(() => {
  try { return { text: setup.value ? buildCiSarifSnippet(setup.value, baseUrl.value, platform.value, scanner.value) : "", error: "" }; }
  catch (cause) { return { text: "", error: cause instanceof Error ? cause.message : "无法生成片段" }; }
});
const snippet = computed(() => rendered.value.text);
const snippetError = computed(() => rendered.value.error);

function clearCredential() { credential.value = ""; expiresAt.value = 0; copiedCredential.value = false; }
function dispose() {
  epoch++; controller?.abort(); controller = undefined;
  if (timer) clearInterval(timer);
  timer = undefined; clearCredential(); setup.value = undefined;
  loading.value = false; issuing.value = false; error.value = "";
}
async function load() {
  const current = ++epoch;
  controller?.abort(); controller = new AbortController();
  clearCredential(); issuing.value = false; setup.value = undefined; loading.value = true; error.value = "";
  try {
    const result = await fetchCiSarifSetup(props.taskId, { signal: controller.signal });
    if (current === epoch && open.value) setup.value = result;
  } catch { if (current === epoch) error.value = "无法读取当前任务的接入信息，请刷新重试或检查访问权限。"; }
  finally { if (current === epoch) loading.value = false; }
}
async function issue() {
  const bound = setup.value;
  if (!bound?.attemptId || !open.value || issuing.value) return;
  const current = epoch;
  clearCredential(); issuing.value = true; error.value = "";
  try {
    const latest = await fetchCiSarifSetup(props.taskId);
    if (current !== epoch || !open.value) return;
    if (latest.attemptId !== bound.attemptId || latest.commitSha !== bound.commitSha) {
      setup.value = latest; throw new Error("binding_changed");
    }
    const result = await issueCiSarifCredential(bound.taskId, bound.attemptId);
    if (current !== epoch || !open.value) return;
    if (result.taskId !== bound.taskId || result.attemptId !== bound.attemptId || result.commitSha !== bound.commitSha || !result.credential || result.expiresAt * 1000 <= Date.now()) throw new Error("binding_changed");
    credential.value = result.credential; expiresAt.value = result.expiresAt * 1000; now.value = Date.now();
  } catch { if (current === epoch) error.value = "无法生成凭证，任务绑定可能已变化或权限不足。请刷新并确认当前批次后重试。"; }
  finally { if (current === epoch) issuing.value = false; }
}
async function copySnippet() {
  try { await navigator.clipboard.writeText(snippet.value); ElMessage.success("上传片段已复制，不包含真实凭证。"); }
  catch { ElMessage.error("复制失败，可在代码区域手动选择复制。"); }
}
async function copyCredential() {
  if (!credential.value || Date.now() >= expiresAt.value) { clearCredential(); return; }
  const current = epoch;
  try {
    await navigator.clipboard.writeText(credential.value);
    if (current !== epoch) return;
    credential.value = ""; copiedCredential.value = true; ElMessage.success("短期凭证已复制，请仅用于本次 CI 上传。");
  } catch { if (current === epoch) error.value = "浏览器未允许复制凭证，请允许剪贴板访问后重试。"; }
}
watch([open, () => props.taskId], ([visible]) => {
  dispose();
  if (visible) {
    timer = setInterval(() => { now.value = Date.now(); if (expiresAt.value && now.value >= expiresAt.value) credential.value = ""; }, 1000);
    void load();
  }
});
onBeforeUnmount(dispose);
</script>

<style scoped>
.sarif-entry { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.sarif-entry h2 { margin: 0 0 8px; font-size: 16px; }
.sarif-entry p, .sarif-guide p { color: var(--el-text-color-regular); line-height: 1.6; }
.sarif-guide h3 { margin: 24px 0 12px; }
.sarif-guide { max-height: 75vh; overflow-y: auto; padding-right: 6px; }
.sarif-target { display: grid; grid-template-columns: 130px minmax(0, 1fr); gap: 10px; }
.sarif-target dd { margin: 0; overflow-wrap: anywhere; }
.sarif-options { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.sarif-snippet { max-height: 300px; overflow: auto; padding: 16px; background: var(--el-fill-color-light); border-radius: 8px; font-size: 12px; line-height: 1.6; }
.sarif-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.sarif-muted { font-size: 12px; }
.sarif-guide details { margin-top: 16px; line-height: 1.8; }
@media (max-width: 600px) { .sarif-options { grid-template-columns: 1fr; } .sarif-target { grid-template-columns: 1fr; } }
</style>
