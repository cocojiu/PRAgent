import type { ApiEndpointMap } from "@/api/endpoint";
import type {
  ApiOperation
} from "@/api/endpoint";
import {
  generatedEndpoint
} from "@/api/generatedEndpoint";
import type {
  ConnectionTestResult,
  NotificationBinding,
  NotificationBindingRequest,
  NotificationBindingStatusRequest,
  NotificationCenter,
  NotificationReadRequest,
  NotificationReport,
  NotificationDelivery,
  NotificationEvent,
  PageResponse
} from "@/types";

type NotificationPageInput = {
  page?: number;
  pageSize?: number;
  status?: string;
  taskId?: number;
};

type NotificationBindingPageInput = {
  page?: number;
  pageSize?: number;
  organization?: string;
  repository?: string;
  provider?: string;
};

export type NotificationApiContract = {
  fetchNotificationBindings: ApiOperation<NotificationBindingPageInput, PageResponse<NotificationBinding>>;
  createNotificationBinding: ApiOperation<NotificationBindingRequest, NotificationBinding>;
  updateNotificationBinding: ApiOperation<{ id: number; payload: NotificationBindingRequest }, NotificationBinding>;
  updateNotificationBindingStatus: ApiOperation<
    { id: number; payload: NotificationBindingStatusRequest },
    NotificationBinding
  >;
  deleteNotificationBinding: ApiOperation<{ id: number }, void>;
  testNotificationBinding: ApiOperation<{ id: number }, ConnectionTestResult>;
  fetchNotificationEvents: ApiOperation<NotificationPageInput, PageResponse<NotificationEvent>>;
  retryNotificationEvent: ApiOperation<{ id: number }, NotificationEvent>;
  fetchNotificationDeliveries: ApiOperation<NotificationPageInput, PageResponse<NotificationDelivery>>;
  fetchNotifications: ApiOperation<undefined, NotificationCenter>;
  markNotificationRead: ApiOperation<NotificationReadRequest, void>;
  fetchNotificationReadKeys: ApiOperation<undefined, string[]>;
  fetchNotificationReport: ApiOperation<{ period?: string }, NotificationReport>;
};

export const notificationEndpoints: ApiEndpointMap<NotificationApiContract> = {
  fetchNotificationBindings: generatedEndpoint(
    "notificationIntegrationControllerListBindings",
    {
      query: input => ({
        page: input.page ?? 1,
        pageSize: input.pageSize ?? 20,
        organization: input.organization,
        repository: input.repository,
        provider: input.provider
      })
    }
  ),
  createNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerCreateBinding",
    {
      body: input => input
    }
  ),
  updateNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerUpdateBinding",
    {
      path: input => ({ id: input.id }),
      body: input => input.payload
    }
  ),
  updateNotificationBindingStatus: generatedEndpoint(
    "notificationIntegrationControllerUpdateBindingStatus",
    {
      path: input => ({ id: input.id }),
      body: input => input.payload
    }
  ),
  deleteNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerDeleteBinding",
    {
      path: input => ({ id: input.id })
    }
  ),
  testNotificationBinding: generatedEndpoint(
    "notificationIntegrationControllerTestBinding",
    {
      path: input => ({ id: input.id })
    }
  ),
  fetchNotificationEvents: generatedEndpoint(
    "notificationIntegrationControllerListEvents",
    {
      query: input => ({
        page: input.page ?? 1,
        pageSize: input.pageSize ?? 20,
        status: input.status,
        taskId: input.taskId
      })
    }
  ),
  retryNotificationEvent: generatedEndpoint(
    "notificationIntegrationControllerRetryEvent",
    {
      path: input => ({ id: input.id })
    }
  ),
  fetchNotificationDeliveries: generatedEndpoint(
    "notificationIntegrationControllerListDeliveries",
    {
      query: input => ({
        page: input.page ?? 1,
        pageSize: input.pageSize ?? 20,
        status: input.status,
        taskId: input.taskId
      })
    }
  ),
  fetchNotifications: generatedEndpoint("notificationControllerGetNotifications", {}),
  markNotificationRead: generatedEndpoint("notificationControllerMarkRead", {
    body: input => input
  }),
  fetchNotificationReadKeys: generatedEndpoint("notificationControllerReadKeys", {}),
  fetchNotificationReport: generatedEndpoint("notificationControllerReport", {
    query: input => ({ period: input.period })
  })
};
