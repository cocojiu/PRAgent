import type { ApiEndpointMap } from "@/api/endpoint";
import type {
  ApiOperation
} from "@/api/endpoint";
import {
  generatedEndpoint
} from "@/api/generatedEndpoint";
import type {
  FrontendPerformanceReport
} from "@/observability/frontendPerformanceBuffer";
import type {
  ChartSlice,
  DashboardLlmQuality,
  DashboardMetric,
  DashboardOverview,
  DashboardRules,
  DataRetentionCleanupAudit,
  DataRetentionCleanupRequest,
  DataRetentionCleanupResponse,
  CacheStats,
  BackupStatus,
  HighRiskReview,
  MessageQueueHealth,
  MessageQueueRequeueResponse,
  PageResponse,
  ReviewTrendPoint,
  SystemHealthItem
} from "@/types";

type DataRetentionCleanupAuditInput = {
  page?: number;
  pageSize?: number;
  mode?: string;
  status?: string;
  backupReference?: string;
};

export type OperationsApiContract = {
  fetchBackupStatus: ApiOperation<undefined, BackupStatus>;
  fetchDashboardOverview: ApiOperation<{ llmTrendDays: number }, DashboardOverview>;
  fetchDashboardSummary: ApiOperation<undefined, DashboardMetric[]>;
  fetchDashboardReviewTrend: ApiOperation<undefined, ReviewTrendPoint[]>;
  fetchDashboardRiskDistribution: ApiOperation<undefined, Required<ChartSlice>[]>;
  fetchDashboardRules: ApiOperation<undefined, DashboardRules>;
  fetchDashboardHighRiskReviews: ApiOperation<undefined, HighRiskReview[]>;
  fetchDashboardLlmQuality: ApiOperation<{ llmTrendDays: number }, DashboardLlmQuality>;
  fetchSystemHealthSummary: ApiOperation<undefined, SystemHealthItem[]>;
  fetchCacheStats: ApiOperation<undefined, CacheStats>;
  cleanupDataRetention: ApiOperation<DataRetentionCleanupRequest | undefined, DataRetentionCleanupResponse>;
  fetchDataRetentionCleanupAudits: ApiOperation<
    DataRetentionCleanupAuditInput,
    PageResponse<DataRetentionCleanupAudit>
  >;
  fetchMessageQueueHealth: ApiOperation<undefined, MessageQueueHealth>;
  requeueMessageQueueTask: ApiOperation<{ taskId: number }, MessageQueueRequeueResponse>;
  reportFrontendPerformance: ApiOperation<FrontendPerformanceReport, void>;
};

export const operationsEndpoints: ApiEndpointMap<OperationsApiContract> = {
  fetchBackupStatus: generatedEndpoint("backupStatusControllerStatus", {}),
  fetchDashboardOverview: generatedEndpoint("dashboardControllerGetOverview", {
    query: input => ({ llmTrendDays: input.llmTrendDays })
  }),
  fetchDashboardSummary: generatedEndpoint("dashboardControllerGetSummary", {}),
  fetchDashboardReviewTrend: generatedEndpoint("dashboardControllerGetReviewTrend", {}),
  fetchDashboardRiskDistribution: generatedEndpoint("dashboardControllerGetRiskDistribution", {}),
  fetchDashboardRules: generatedEndpoint("dashboardControllerGetRules", {}),
  fetchDashboardHighRiskReviews: generatedEndpoint("dashboardControllerGetHighRiskReviews", {}),
  fetchDashboardLlmQuality: generatedEndpoint("dashboardControllerGetLlmQuality", {
    query: input => ({ llmTrendDays: input.llmTrendDays })
  }),
  fetchSystemHealthSummary: generatedEndpoint("systemHealthControllerGetSystemHealthSummary", {}),
  fetchCacheStats: generatedEndpoint("cacheStatsControllerGetStats", {}),
  cleanupDataRetention: generatedEndpoint("dataRetentionControllerCleanup", {
    body: input => input
  }),
  fetchDataRetentionCleanupAudits: generatedEndpoint("dataRetentionControllerListCleanupAudits", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      mode: input.mode,
      status: input.status,
      backupReference: input.backupReference
    })
  }),
  fetchMessageQueueHealth: generatedEndpoint("messageQueueHealthControllerGetHealth", {}),
  requeueMessageQueueTask: generatedEndpoint("messageQueueHealthControllerRequeueTask", {
    path: input => ({ taskId: input.taskId })
  }),
  reportFrontendPerformance: {
    ...generatedEndpoint("frontendPerformanceControllerRecordPerformance", {
      body: input => input
    }),
    observe: false
  }
};
