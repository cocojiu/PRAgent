import { apiRequest, type ApiRequestOptions } from "@/api/contracts";
import type { NotificationReadRequest } from "@/types";

export const fetchNotifications = (options: ApiRequestOptions = {}) => apiRequest("fetchNotifications", undefined, options);
export const markNotificationRead = (request: NotificationReadRequest) =>
  apiRequest("markNotificationRead", request);
export const fetchNotificationReadKeys = (options: ApiRequestOptions = {}) => apiRequest("fetchNotificationReadKeys", undefined, options);
export const fetchNotificationReport = (period: string = "DAILY") =>
  apiRequest("fetchNotificationReport", { period });
