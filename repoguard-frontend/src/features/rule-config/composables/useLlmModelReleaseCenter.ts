import { computed, getCurrentScope, onScopeDispose, reactive, ref, watch } from "vue";
import type { ApiRequestOptions } from "@/api/contracts";
import {
  fetchLlmEvaluationReports,
  fetchLlmModelReleaseAudits,
  fetchLlmModelReleaseCenter,
  fetchLlmModelReleaseRuntimeMetrics,
  exportLlmModelReleaseAudits,
  promoteLlmModelRelease,
  registerLlmModelShadowRelease,
  verifyLlmModelReleaseAudit,
  rollbackLlmModelRelease,
  transitionLlmEvaluationReportLifecycle
} from "@/api/config";
import type {
  LlmEvaluationReport,
  LlmModelReleaseAudit,
  LlmModelReleaseAuditExport,
  LlmModelRelease,
  LlmModelReleaseCenter,
  LlmModelReleaseMetric,
  LlmModelReleaseRequest
} from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { formatDateTime } from "@/utils/dateTime";

const readKeys = ["center", "reports", "runtimeMetrics", "audits"] as const;
type ReadKey = typeof readKeys[number];
type ReadState = { loading: boolean; error: string; lastSuccessAt: string; lastSuccessLabel: string };

export const buildLlmModelReleaseRequest = (
  report: LlmEvaluationReport,
  releaseKey: string,
  trafficPercent: number
): LlmModelReleaseRequest => ({
  releaseKey: releaseKey.trim(),
  provider: report.provider,
  modelName: report.model,
  promptVersion: report.promptVersion,
  contextVersion: report.contextVersion,
  schemaVersion: report.schemaVersion,
  datasetId: report.datasetId,
  datasetVersion: report.datasetVersion,
  datasetFingerprint: report.sampleFingerprint,
  trafficPercent,
  // These fields are retained for the compatibility request shape only. The server replaces
  // them with the immutable report values before writing or promoting a release.
  qualityGatePassed: report.eligible && report.blockers.length === 0,
  precisionRate: report.precision,
  recallRate: report.recall,
  anchorRate: report.anchorRate,
  duplicateRate: report.duplicateRate,
  parseFailureRate: report.parseFailureRate,
  p95LatencyMs: report.metrics.p95LatencyMs,
  averageCost: report.metrics.averageCostPerSample,
  totalTokens: report.totalTokens,
  blockers: report.blockers,
  evaluationReportId: report.id
});

export const useLlmModelReleaseCenter = () => {
  const center = ref<LlmModelReleaseCenter | null>(null);
  const runtimeMetrics = ref<LlmModelReleaseMetric[]>([]);
  const reports = ref<LlmEvaluationReport[]>([]);
  const audits = ref<LlmModelReleaseAudit[]>([]);
  const auditTotal = ref(0);
  const auditPage = ref(1);
  const moduleStates = reactive(Object.fromEntries(readKeys.map(key => [key, {
    loading: false, error: "", lastSuccessAt: "", lastSuccessLabel: ""
  }])) as Record<ReadKey, ReadState>);
  const auditLoading = computed(() => moduleStates.audits.loading);
  const auditOperation = ref("");
  const auditFilterAction = ref("");
  const auditOperator = ref("");
  const auditVerification = ref<Record<number, string>>({});
  const selectedReportId = ref<number>();
  const trendDays = ref(30);
  const releaseKey = ref("");
  const canaryTraffic = ref(10);
  const loading = computed(() => readKeys.some(key => moduleStates[key].loading));
  const action = ref("");
  const errorMessage = ref("");
  let disposed = false;
  const controllers: Partial<Record<ReadKey, AbortController>> = {};

  const selectedReport = computed(() =>
    reports.value.find((report) => report.id === selectedReportId.value) ?? null
  );
  const releaseDataReady = computed(() => !disposed && center.value !== null
    && [moduleStates.center, moduleStates.reports].every(state =>
      !state.loading && !state.error && Boolean(state.lastSuccessAt)));

  const cancelRead = (key: ReadKey) => {
    controllers[key]?.abort();
    delete controllers[key];
    moduleStates[key].loading = false;
  };
  const loadRead = async <T>(key: ReadKey, request: (options: ApiRequestOptions) => Promise<T>,
    apply: (value: T) => void, fallback: string, label: string) => {
    if (disposed) return;
    cancelRead(key);
    const controller = new AbortController();
    controllers[key] = controller;
    const current = () => !disposed && controllers[key] === controller && !controller.signal.aborted;
    const state = moduleStates[key];
    state.loading = true;
    state.error = "";
    try {
      const value = await request({ signal: controller.signal });
      if (!current()) return;
      apply(value);
      state.lastSuccessAt = formatDateTime(new Date());
      state.lastSuccessLabel = label;
    } catch (error) {
      if (current()) state.error = getErrorMessage(error, fallback);
    } finally {
      if (current()) { state.loading = false; delete controllers[key]; }
    }
  };

  const load = async () => {
    if (disposed) return;
    const days = trendDays.value;
    errorMessage.value = "";
    await Promise.all([
      loadRead("center", options => fetchLlmModelReleaseCenter(days, options),
        value => { center.value = value; }, "模型发布状态加载失败", `${days} 天窗口`),
      loadRead("reports", options => fetchLlmEvaluationReports(50, options), value => {
        reports.value = value;
        if (!value.some(report => report.id === selectedReportId.value)) selectedReportId.value = value[0]?.id;
      }, "评估报告加载失败", "最近 50 份报告"),
      loadRead("runtimeMetrics", options => fetchLlmModelReleaseRuntimeMetrics({ days, limit: 168 }, options),
        value => { runtimeMetrics.value = value; }, "发布运行指标加载失败", `${days} 天窗口`),
      loadAudits(1)
    ]);
  };

  const loadAudits = (page = auditPage.value) => {
    const query = { releaseKey: releaseKey.value.trim() || undefined, operator: auditOperator.value.trim() || undefined,
      action: auditFilterAction.value || undefined, page, pageSize: 20 };
    return loadRead("audits", options => fetchLlmModelReleaseAudits(query, options), result => {
      audits.value = result.items;
      auditTotal.value = result.total;
      auditPage.value = page;
    }, "发布审计加载失败", `${query.releaseKey || "全部版本"} / ${query.operator || "全部操作者"} / ${query.action || "全部动作"} / 第 ${page} 页`);
  };
  watch([releaseKey, auditOperator, auditFilterAction], () => {
    if (disposed) return;
    cancelRead("audits");
    moduleStates.audits.error = "筛选条件已变更，请刷新审计";
  }, { flush: "sync" });
  const dispose = () => { disposed = true; readKeys.forEach(cancelRead); };
  if (getCurrentScope()) onScopeDispose(dispose);

  const verifyAudit = async (auditId: number) => {
    auditOperation.value = `verify-${auditId}`;
    try {
      const result = await verifyLlmModelReleaseAudit(auditId);
      auditVerification.value = { ...auditVerification.value, [auditId]: result.status };
      await loadAudits(auditPage.value);
      return result;
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "发布审计校验失败");
      return null;
    } finally {
      auditOperation.value = "";
    }
  };

  const exportAudits = async (format: "json" | "csv" = "csv"): Promise<LlmModelReleaseAuditExport | null> => {
    auditOperation.value = `export-${format}`;
    try {
      return await exportLlmModelReleaseAudits({
        releaseKey: releaseKey.value.trim() || undefined,
        operator: auditOperator.value.trim() || undefined,
        action: auditFilterAction.value || undefined,
        format
      });
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "发布审计导出失败");
      return null;
    } finally {
      auditOperation.value = "";
    }
  };

  const runAction = async (name: string, callback: () => Promise<unknown>) => {
    action.value = name;
    errorMessage.value = "";
    try {
      await callback();
      await load();
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "模型发布操作失败");
    } finally {
      action.value = "";
    }
  };

  const registerShadow = async () => {
    const report = selectedReport.value;
    if (!releaseKey.value.trim() || !report) {
      errorMessage.value = "请先填写发布键并选择可复用的服务端评估报告";
      return;
    }
    if (report.status !== "COMPLETED") {
      errorMessage.value = "小样本验收报告不能用于模型发布";
      return;
    }
    if (!releaseDataReady.value) {
      errorMessage.value = "请刷新发布状态和评估报告后再操作";
      return;
    }
    await runAction("shadow", () =>
      registerLlmModelShadowRelease(buildLlmModelReleaseRequest(report, releaseKey.value, 0))
    );
  };

  const promote = async (release?: LlmModelRelease) => {
    const report = release?.evaluationReportId
      ? reports.value.find((item) => item.id === release.evaluationReportId)
      : selectedReport.value;
    const key = release?.releaseKey ?? releaseKey.value;
    if (!key.trim() || !report) {
      errorMessage.value = "请先选择与发布版本匹配的服务端评估报告";
      return;
    }
    if (report.status !== "COMPLETED") {
      errorMessage.value = "小样本验收报告不能用于模型发布";
      return;
    }
    if (!releaseDataReady.value) {
      errorMessage.value = "请刷新发布状态和评估报告后再操作";
      return;
    }
    const trafficPercent = release?.state === "CANARY"
      ? Math.max(1, Math.min(100, Math.round(canaryTraffic.value)))
      : Math.max(1, Math.min(100, Math.round(canaryTraffic.value)));
    await runAction(`promote-${key}`, () =>
      promoteLlmModelRelease(buildLlmModelReleaseRequest(report, key, trafficPercent))
    );
  };

  const rollback = async (releaseId: number, reason: string) => {
    if (!reason.trim()) {
      errorMessage.value = "回滚必须填写原因";
      return;
    }
    await runAction(`rollback-${releaseId}`, () =>
      rollbackLlmModelRelease(releaseId, { reason: reason.trim() })
    );
  };

  const transitionReportLifecycle = async (
    reportId: number,
    lifecycleAction: "FREEZE" | "REVOKE_AUTHORIZATION" | "DELETE",
    reason: string
  ) => {
    if (!reason.trim()) {
      errorMessage.value = "生命周期操作必须填写原因";
      return;
    }
    await runAction(`report-${lifecycleAction.toLowerCase()}-${reportId}`, () =>
      transitionLlmEvaluationReportLifecycle(reportId, {
        action: lifecycleAction,
        reason: reason.trim(),
        idempotencyKey: `${lifecycleAction.toLowerCase()}-${reportId}-${Date.now()}`
      })
    );
  };

  return {
    action,
    auditFilterAction,
    auditLoading,
    auditOperation,
    auditOperator,
    auditPage,
    auditTotal,
    auditVerification,
    audits,
    canaryTraffic,
    center,
    errorMessage,
    loading,
    moduleStates,
    releaseDataReady,
    dispose,
    load,
    loadAudits,
    promote,
    registerShadow,
    releaseKey,
    reports,
    runtimeMetrics,
    exportAudits,
    verifyAudit,
    rollback,
    selectedReport,
    selectedReportId,
    trendDays,
    transitionReportLifecycle
  };
};

export const selectedEvaluationSampleIds = (diagnostic: boolean, text: string): string[] | undefined => {
  if (!diagnostic) return undefined;
  const ids = [...new Set(text.split(/[,，\s]+/).filter(Boolean))];
  if (!ids.length || ids.length > 100 || ids.some(id => !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(id))) {
    throw new Error("请输入 1 至 100 个有效样本 ID");
  }
  return ids;
};
