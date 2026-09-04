import { computed, ref } from "vue";
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
  const auditLoading = ref(false);
  const auditOperation = ref("");
  const auditFilterAction = ref("");
  const auditOperator = ref("");
  const auditVerification = ref<Record<number, string>>({});
  const selectedReportId = ref<number>();
  const trendDays = ref(30);
  const releaseKey = ref("");
  const canaryTraffic = ref(10);
  const loading = ref(false);
  const action = ref("");
  const errorMessage = ref("");
  let requestEpoch = 0;

  const selectedReport = computed(() =>
    reports.value.find((report) => report.id === selectedReportId.value) ?? null
  );

  const load = async () => {
    const epoch = ++requestEpoch;
    loading.value = true;
    errorMessage.value = "";
    try {
      const [nextCenter, nextReports, nextRuntimeMetrics] = await Promise.all([
        fetchLlmModelReleaseCenter(trendDays.value),
        fetchLlmEvaluationReports(50),
        fetchLlmModelReleaseRuntimeMetrics({ days: trendDays.value, limit: 168 }),
        loadAudits(1)
      ]);
      if (epoch !== requestEpoch) return;
      center.value = nextCenter;
      reports.value = nextReports;
      runtimeMetrics.value = nextRuntimeMetrics;
      if (!nextReports.some((report) => report.id === selectedReportId.value)) {
        selectedReportId.value = nextReports[0]?.id;
      }
    } catch (error) {
      if (epoch === requestEpoch) errorMessage.value = getErrorMessage(error, "模型发布中心加载失败");
    } finally {
      if (epoch === requestEpoch) loading.value = false;
    }
  };

  const loadAudits = async (page = auditPage.value) => {
    auditLoading.value = true;
    try {
      const result = await fetchLlmModelReleaseAudits({
        releaseKey: releaseKey.value.trim() || undefined,
        operator: auditOperator.value.trim() || undefined,
        action: auditFilterAction.value || undefined,
        page,
        pageSize: 20
      });
      audits.value = result.items;
      auditTotal.value = result.total;
      auditPage.value = page;
    } catch (error) {
      errorMessage.value = getErrorMessage(error, "发布审计加载失败");
    } finally {
      auditLoading.value = false;
    }
  };

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
