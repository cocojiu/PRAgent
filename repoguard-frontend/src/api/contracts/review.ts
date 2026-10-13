import type { ApiEndpointMap } from "@/api/endpoint";
import type {
  CiSarifCredential,
  CiSarifSetup
} from "@/api/ciSarif";
import type {
  ApiOperation
} from "@/api/endpoint";
import {
  generatedEndpoint
} from "@/api/generatedEndpoint";
import type {
  ChangedFile,
  FindingFeedbackRequest,
  FindingFeedbackResponse,
  HumanReviewRequest,
  HumanReviewResponse,
  MissingTest,
  ReviewFinding,
  ReviewRetryResponse,
  ReviewTaskStatus,
  ReviewTaskSummary,
  TimelineItem
} from "@/api/generated/reviewDetailTypes";
import {
  isCodeownersRecommendations,
  isCodeownersAcceptance,
  isReviewAssignmentOptions,
  isReviewMemberAssignment,
  isGithubFeedbackDiagnostics,
  isFeedbackSummary,
  isReviewTaskSummary
} from "@/api/responseValidation";
import type {
  GithubCommentPreview,
  GithubCommentPublicationHistory,
  GithubCommentPublicationItems,
  GithubCommentPublish,
  GithubFeedbackDiagnostics,
  FeedbackSummary,
  CodeownersRecommendations,
  CodeownersAcceptance,
  CodeownersAcceptanceRequest,
  ReviewAssignmentOptions,
  ReviewMemberAssignmentRequest,
  ReviewMemberAssignment,
  GithubPullRequestOptions,
  ManualReviewRequest,
  ManualReviewResponse,
  PageResponse,
  ReviewQuery,
  ReviewExecutionAttempt,
  ReviewExecutionAttemptResult,
  ReviewAttemptComparison,
  ReviewTask,
  ReviewTaskListSummary,
  ReviewTaskListSummaryQuery
} from "@/types";

type ReviewDetailPageInput = {
  id: number;
  page?: number;
  pageSize?: number;
};

type ReviewFindingsPageInput = ReviewDetailPageInput & {
  severity?: string;
  category?: string;
  feedbackStatus?: string;
  source?: string;
};

type ReviewChangedFilesPageInput = ReviewDetailPageInput & {
  hasFinding?: boolean;
};

type GithubCommentPreviewInput = ReviewDetailPageInput & {
  commentableOnly?: boolean;
};

export type ReviewApiContract = {
  fetchCiSarifSetup: ApiOperation<{ taskId: number }, CiSarifSetup>;
  issueCiSarifCredential: ApiOperation<{ taskId: number; attemptId: number }, CiSarifCredential>;
  fetchFeedbackSummary: ApiOperation<undefined, FeedbackSummary>;
  fetchReviewAssignmentOptions: ApiOperation<{ taskId: number; search?: string }, ReviewAssignmentOptions>;
  confirmReviewMemberAssignment: ApiOperation<{ taskId: number; input: ReviewMemberAssignmentRequest }, ReviewMemberAssignment>;
  fetchCodeownersRecommendations: ApiOperation<{ taskId: number }, CodeownersRecommendations>;
  acceptCodeownersRecommendation: ApiOperation<{ taskId: number; input: CodeownersAcceptanceRequest }, CodeownersAcceptance>;
  fetchReviews: ApiOperation<ReviewQuery, PageResponse<ReviewTask>>;
  fetchReviewListSummary: ApiOperation<ReviewTaskListSummaryQuery, ReviewTaskListSummary>;
  fetchReviewDetail: ApiOperation<{ id: number }, ReviewTaskSummary>;
  fetchReviewFindings: ApiOperation<ReviewFindingsPageInput, PageResponse<ReviewFinding>>;
  fetchReviewChangedFiles: ApiOperation<ReviewChangedFilesPageInput, PageResponse<ChangedFile>>;
  fetchReviewMissingTests: ApiOperation<ReviewDetailPageInput, PageResponse<MissingTest>>;
  fetchReviewTimeline: ApiOperation<{ id: number; limit?: number }, TimelineItem[]>;
  fetchReviewExecutionAttempts: ApiOperation<{ taskId: number }, ReviewExecutionAttempt[]>;
  fetchReviewExecutionAttemptResult: ApiOperation<
    { taskId: number; attemptId: number; page?: number; pageSize?: number },
    ReviewExecutionAttemptResult
  >;
  fetchReviewAttemptComparison: ApiOperation<
    { taskId: number; candidateAttemptId: number; baselineAttemptId?: number; page?: number; pageSize?: number },
    ReviewAttemptComparison
  >;
  fetchReviewRepositories: ApiOperation<undefined, string[]>;
  fetchReviewStatus: ApiOperation<{ id: number }, ReviewTaskStatus>;
  fetchGithubCommentPreview: ApiOperation<GithubCommentPreviewInput, GithubCommentPreview>;
  fetchGithubCommentPublicationHistory: ApiOperation<
    { id: number; page?: number; pageSize?: number; status?: string },
    GithubCommentPublicationHistory
  >;
  fetchGithubCommentPublicationBatches: ApiOperation<
    { id: number; page?: number; pageSize?: number; status?: string },
    GithubCommentPublicationHistory
  >;
  fetchGithubCommentPublicationItems: ApiOperation<
    { id: number; batchId: number; afterId?: number; pageSize?: number },
    GithubCommentPublicationItems
  >;
  publishGithubComments: ApiOperation<{ id: number }, GithubCommentPublish>;
  submitHumanReview: ApiOperation<{ id: number; payload: HumanReviewRequest }, HumanReviewResponse>;
  updateFindingFeedback: ApiOperation<
    { id: number; findingId: number; payload: FindingFeedbackRequest },
    FindingFeedbackResponse
  >;
  retryReview: ApiOperation<{ id: number }, ReviewRetryResponse>;
  fetchGithubPullRequestOptions: ApiOperation<undefined, GithubPullRequestOptions>;
  triggerManualReview: ApiOperation<ManualReviewRequest, ManualReviewResponse>;
  fetchGithubFeedback: ApiOperation<{ limit?: number }, GithubFeedbackDiagnostics>;
  retryGithubFeedback: ApiOperation<{ id: number }, void>;
};

export const reviewEndpoints: ApiEndpointMap<ReviewApiContract> = {
  fetchCiSarifSetup: generatedEndpoint("ciSarifSetupControllerGetSetup", {
    path: input => ({ taskId: input.taskId })
  }),
  issueCiSarifCredential: generatedEndpoint("ciSarifControllerIssueCredential", {
    path: input => ({ taskId: input.taskId }),
    query: input => ({ attemptId: input.attemptId })
  }),
  fetchFeedbackSummary: {
    ...generatedEndpoint("feedbackSummaryControllerSummary", {}),
    validateResponse: isFeedbackSummary
  },
  fetchReviewAssignmentOptions: {
    ...generatedEndpoint("reviewMemberAssignmentControllerOptions", { path: input => ({ taskId: input.taskId }), query: input => ({ search: input.search }) }),
    validateResponse: isReviewAssignmentOptions
  },
  confirmReviewMemberAssignment: {
    ...generatedEndpoint("reviewMemberAssignmentControllerConfirm", { path: input => ({ taskId: input.taskId }), body: input => input.input }),
    validateResponse: isReviewMemberAssignment
  },
  fetchCodeownersRecommendations: {
    ...generatedEndpoint("codeownersRecommendationControllerRecommendations", { path: input => ({ taskId: input.taskId }) }),
    validateResponse: isCodeownersRecommendations
  },
  acceptCodeownersRecommendation: {
    ...generatedEndpoint("codeownersRecommendationControllerAccept", { path: input => ({ taskId: input.taskId }), body: input => input.input }),
    validateResponse: isCodeownersAcceptance
  },
  fetchReviews: generatedEndpoint("reviewControllerListReviews", {
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      repository: input.repository,
      status: input.status,
      riskLevel: input.riskLevel,
      source: input.source,
      triggerSource: input.triggerSource,
      keyword: input.keyword,
      cursor: input.cursor
    })
  }),
  fetchReviewListSummary: generatedEndpoint("reviewControllerGetReviewListSummary", {
    query: input => ({
      repository: input.repository,
      status: input.status,
      riskLevel: input.riskLevel,
      source: input.source,
      triggerSource: input.triggerSource,
      keyword: input.keyword
    })
  }),
  fetchReviewDetail: {
    ...generatedEndpoint("reviewControllerGetReviewDetail", {
      path: input => ({ id: input.id })
    }),
    validateResponse: isReviewTaskSummary
  },
  fetchReviewFindings: generatedEndpoint("reviewControllerListReviewFindings", {
    path: input => ({ id: input.id }),
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      severity: input.severity,
      category: input.category,
      feedbackStatus: input.feedbackStatus,
      source: input.source
    })
  }),
  fetchReviewChangedFiles: generatedEndpoint("reviewControllerListChangedFiles", {
    path: input => ({ id: input.id }),
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      hasFinding: input.hasFinding
    })
  }),
  fetchReviewMissingTests: generatedEndpoint("reviewControllerListMissingTests", {
    path: input => ({ id: input.id }),
    query: input => ({ page: input.page, pageSize: input.pageSize })
  }),
  fetchReviewTimeline: generatedEndpoint("reviewControllerListReviewTimeline", {
    path: input => ({ id: input.id }),
    query: input => ({ limit: input.limit })
  }),
  fetchReviewExecutionAttempts: generatedEndpoint("reviewExecutionAttemptControllerList", {
    path: input => ({ taskId: input.taskId })
  }),
  fetchReviewExecutionAttemptResult: generatedEndpoint("reviewExecutionAttemptControllerGetResult", {
    path: input => ({ taskId: input.taskId, attemptId: input.attemptId }),
    query: input => ({ page: input.page, pageSize: input.pageSize })
  }),
  fetchReviewAttemptComparison: generatedEndpoint("reviewExecutionAttemptComparisonControllerCompare", {
    path: input => ({ taskId: input.taskId, candidateAttemptId: input.candidateAttemptId }),
    query: input => ({
      baselineAttemptId: input.baselineAttemptId,
      page: input.page,
      pageSize: input.pageSize
    })
  }),
  fetchReviewRepositories: generatedEndpoint("reviewControllerListRepositories", {}),
  fetchReviewStatus: generatedEndpoint("reviewControllerGetReviewStatus", {
    path: input => ({ id: input.id })
  }),
  fetchGithubCommentPreview: generatedEndpoint("reviewControllerGetGithubCommentPreview", {
    path: input => ({ id: input.id }),
    query: input => ({
      page: input.page,
      pageSize: input.pageSize,
      commentableOnly: input.commentableOnly
    })
  }),
  fetchGithubCommentPublicationHistory: generatedEndpoint(
    "reviewControllerGetGithubCommentPublicationHistory",
    {
      path: input => ({ id: input.id }),
      query: input => ({ page: input.page, pageSize: input.pageSize, status: input.status })
    }
  ),
  fetchGithubCommentPublicationBatches: generatedEndpoint("githubCommentHistoryControllerGetBatches", {
    path: input => ({ taskId: input.id }),
    query: input => ({ page: input.page, pageSize: input.pageSize, status: input.status })
  }),
  fetchGithubCommentPublicationItems: generatedEndpoint("githubCommentHistoryControllerGetItems", {
    path: input => ({ taskId: input.id, batchId: input.batchId }),
    query: input => ({ afterId: input.afterId, pageSize: input.pageSize })
  }),
  publishGithubComments: generatedEndpoint("reviewControllerPublishGithubComments", {
    path: input => ({ id: input.id })
  }),
  submitHumanReview: generatedEndpoint("reviewControllerSubmitHumanReview", {
    path: input => ({ id: input.id }),
    body: input => input.payload
  }),
  updateFindingFeedback: generatedEndpoint("reviewControllerUpdateFindingFeedback", {
    path: input => ({ id: input.id, findingId: input.findingId }),
    body: input => input.payload
  }),
  retryReview: generatedEndpoint("reviewControllerRetryReview", {
    path: input => ({ id: input.id })
  }),
  fetchGithubPullRequestOptions: generatedEndpoint(
    "reviewControllerListConfiguredGithubPullRequests",
    {}
  ),
  triggerManualReview: generatedEndpoint("reviewControllerTriggerManualReview", {
    body: input => input
  }),
  fetchGithubFeedback: {
    ...generatedEndpoint("githubFeedbackControllerDiagnostics", {
      query: input => ({ limit: input.limit })
    }),
    validateResponse: isGithubFeedbackDiagnostics
  },
  retryGithubFeedback: generatedEndpoint("githubFeedbackControllerRetry", {
    path: input => ({ id: input.id })
  })
};
