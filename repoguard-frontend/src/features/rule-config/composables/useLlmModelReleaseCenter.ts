import { computed, ref } from "vue";
import {
  fetchLlmEvaluationReports,
  fetchLlmModelReleaseCenter,
  promoteLlmModelRelease,
  registerLlmModelShadowRelease,
  rollbackLlmModelRelease
} from "@/api/config";
import type {
  LlmEvaluationReport,
  LlmModelRelease,
  LlmModelReleaseCenter,
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
  const reports = ref<LlmEvaluationReport[]>([]);
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
      const [nextCenter, nextReports] = await Promise.all([
        fetchLlmModelReleaseCenter(trendDays.value),
        fetchLlmEvaluationReports(50)
      ]);
      if (epoch !== requestEpoch) return;
      center.value = nextCenter;
      reports.value = nextReports;
      if (!nextReports.some((report) => report.id === selectedReportId.value)) {
        selectedReportId.value = nextReports[0]?.id;
      }
    } catch (error) {
      if (epoch === requestEpoch) errorMessage.value = getErrorMessage(error, "模型发布中心加载失败");
    } finally {
      if (epoch === requestEpoch) loading.value = false;
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

  return {
    action,
    canaryTraffic,
    center,
    errorMessage,
    loading,
    load,
    promote,
    registerShadow,
    releaseKey,
    reports,
    rollback,
    selectedReport,
    selectedReportId,
    trendDays
  };
};
