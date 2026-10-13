import type { ApiEndpointMap } from "@/api/endpoint";
import type {
  ApiOperation
} from "@/api/endpoint";
import {
  generatedEndpoint
} from "@/api/generatedEndpoint";
import type {
  LlmModelRelease,
  LlmModelReleaseAudit,
  LlmModelReleaseAuditExport,
  LlmModelReleaseAuditVerification,
  LlmModelReleaseCenter,
  LlmModelReleaseMetric,
  LlmModelReleaseDrift,
  LlmModelReleaseDriftRepair,
  LlmModelReleaseDriftRepairRequest,
  LlmModelReleaseRequest,
  LlmModelRollbackRequest,
  LlmEvaluationExport,
  LlmEvaluationReport,
  LlmEvaluationReportComparison,
  LlmEvaluationReportLifecycleRequest,
  LlmEvaluationRequest,
  LlmEvaluationRun,
  LlmEvaluationRunRequest,
  PageResponse,
  ReviewCalibrationQueue
} from "@/types";

export type ModelQualityApiContract = {
  fetchReviewCalibrationQueue: ApiOperation<
    { ruleId: string; limit?: number; includeIgnored?: boolean },
    ReviewCalibrationQueue
  >;
  fetchLlmModelReleaseCenter: ApiOperation<{ trendDays?: number }, LlmModelReleaseCenter>;
  fetchLlmModelReleaseRuntimeMetrics: ApiOperation<
    { releaseKey?: string; days?: number; limit?: number },
    LlmModelReleaseMetric[]
  >;
  fetchLlmModelReleaseDrift: ApiOperation<undefined, LlmModelReleaseDrift>;
  repairLlmModelReleaseDrift: ApiOperation<LlmModelReleaseDriftRepairRequest, LlmModelReleaseDriftRepair>;
  fetchLlmModelReleaseAudits: ApiOperation<{
    releaseId?: number;
    releaseKey?: string;
    operator?: string;
    action?: string;
    from?: string;
    to?: string;
    page?: number;
    pageSize?: number;
  }, PageResponse<LlmModelReleaseAudit>>;
  verifyLlmModelReleaseAudit: ApiOperation<{ auditId: number }, LlmModelReleaseAuditVerification>;
  exportLlmModelReleaseAudits: ApiOperation<{
    releaseId?: number;
    releaseKey?: string;
    operator?: string;
    action?: string;
    from?: string;
    to?: string;
    format?: string;
  }, LlmModelReleaseAuditExport>;
  registerLlmModelShadowRelease: ApiOperation<LlmModelReleaseRequest, LlmModelRelease>;
  promoteLlmModelRelease: ApiOperation<LlmModelReleaseRequest, LlmModelRelease>;
  createLlmEvaluationReport: ApiOperation<LlmEvaluationRequest, LlmEvaluationReport>;
  startLlmEvaluationRun: ApiOperation<LlmEvaluationRunRequest, LlmEvaluationRun>;
  fetchLlmEvaluationRun: ApiOperation<{ runId: string }, LlmEvaluationRun>;
  cancelLlmEvaluationRun: ApiOperation<{ runId: string }, LlmEvaluationRun>;
  fetchLlmEvaluationReports: ApiOperation<{ limit?: number }, LlmEvaluationReport[]>;
  fetchLlmEvaluationReport: ApiOperation<{ reportId: number }, LlmEvaluationReport>;
  compareLlmEvaluationReports: ApiOperation<
    { reportId: number; candidateReportId: number },
    LlmEvaluationReportComparison
  >;
  exportLlmEvaluationReport: ApiOperation<{ reportId: number; format?: string }, LlmEvaluationExport>;
  transitionLlmEvaluationReportLifecycle: ApiOperation<
    { reportId: number; payload: LlmEvaluationReportLifecycleRequest },
    LlmEvaluationReport
  >;
  rollbackLlmModelRelease: ApiOperation<
    { releaseId: number; payload: LlmModelRollbackRequest },
    LlmModelRelease
  >;
  safetyRevokeLlmModelRelease: ApiOperation<{ releaseId: number; payload: LlmModelRollbackRequest }, LlmModelRelease>;
};

export const modelQualityEndpoints: ApiEndpointMap<ModelQualityApiContract> = {
  fetchReviewCalibrationQueue: generatedEndpoint("reviewCalibrationControllerGetReviewCalibrationQueue", {
    query: input => ({
      ruleId: input.ruleId,
      limit: input.limit ?? 30,
      includeIgnored: input.includeIgnored ?? false
    })
  }),
  fetchLlmModelReleaseCenter: generatedEndpoint("reviewCalibrationControllerGetModelReleaseCenter", {
    query: input => ({ trendDays: input.trendDays })
  }),
  fetchLlmModelReleaseRuntimeMetrics: generatedEndpoint("reviewCalibrationControllerListModelReleaseRuntimeMetrics", {
    query: input => ({ releaseKey: input.releaseKey, days: input.days, limit: input.limit })
  }),
  fetchLlmModelReleaseDrift: generatedEndpoint("reviewCalibrationControllerInspectModelReleaseDrift", {}),
  repairLlmModelReleaseDrift: generatedEndpoint("reviewCalibrationControllerRepairModelReleaseDrift", {
    body: input => input
  }),
  fetchLlmModelReleaseAudits: generatedEndpoint("reviewCalibrationControllerListModelReleaseAudits", {
    query: input => ({
      releaseId: input.releaseId,
      releaseKey: input.releaseKey,
      operator: input.operator,
      action: input.action,
      from: input.from,
      to: input.to,
      page: input.page,
      pageSize: input.pageSize
    })
  }),
  verifyLlmModelReleaseAudit: generatedEndpoint("reviewCalibrationControllerVerifyModelReleaseAudit", {
    path: input => ({ auditId: input.auditId })
  }),
  exportLlmModelReleaseAudits: generatedEndpoint("reviewCalibrationControllerExportModelReleaseAudits", {
    query: input => ({
      releaseId: input.releaseId,
      releaseKey: input.releaseKey,
      operator: input.operator,
      action: input.action,
      from: input.from,
      to: input.to,
      format: input.format
    })
  }),
  registerLlmModelShadowRelease: generatedEndpoint("reviewCalibrationControllerRegisterShadowRelease", {
    body: input => input
  }),
  promoteLlmModelRelease: generatedEndpoint("reviewCalibrationControllerPromoteModelRelease", {
    body: input => input
  }),
  createLlmEvaluationReport: generatedEndpoint("reviewCalibrationControllerCreateEvaluationReport", {
    body: input => input
  }),
  startLlmEvaluationRun: generatedEndpoint("reviewCalibrationControllerStartEvaluationRun", {
    body: input => input
  }),
  fetchLlmEvaluationRun: generatedEndpoint("reviewCalibrationControllerGetEvaluationRun", {
    path: input => ({ runId: input.runId })
  }),
  cancelLlmEvaluationRun: generatedEndpoint("reviewCalibrationControllerCancelEvaluationRun", {
    path: input => ({ runId: input.runId })
  }),
  fetchLlmEvaluationReports: generatedEndpoint("reviewCalibrationControllerListEvaluationReports", {
    query: input => ({ limit: input.limit })
  }),
  fetchLlmEvaluationReport: generatedEndpoint("reviewCalibrationControllerGetEvaluationReport", {
    path: input => ({ reportId: input.reportId })
  }),
  compareLlmEvaluationReports: generatedEndpoint("reviewCalibrationControllerCompareEvaluationReports", {
    path: input => ({ reportId: input.reportId, candidateReportId: input.candidateReportId })
  }),
  exportLlmEvaluationReport: generatedEndpoint("reviewCalibrationControllerExportEvaluationReport", {
    path: input => ({ reportId: input.reportId }),
    query: input => ({ format: input.format })
  }),
  transitionLlmEvaluationReportLifecycle: generatedEndpoint("reviewCalibrationControllerTransitionEvaluationReportLifecycle", {
    path: input => ({ reportId: input.reportId }),
    body: input => input.payload
  }),
  rollbackLlmModelRelease: generatedEndpoint("reviewCalibrationControllerRollbackModelRelease", {
    path: input => ({ releaseId: input.releaseId }),
    body: input => input.payload
  }),
  safetyRevokeLlmModelRelease: generatedEndpoint("reviewCalibrationControllerSafetyRevokeModelRelease", {
    path: input => ({ releaseId: input.releaseId }),
    body: input => input.payload
  })
};
