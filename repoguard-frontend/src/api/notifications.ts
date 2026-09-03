import { apiRequest } from "@/api/contracts";
import type { NotificationReadRequest } from "@/types";

export const fetchNotifications = () => apiRequest("fetchNotifications", undefined);
export const markNotificationRead = (request: NotificationReadRequest) =>
  apiRequest("markNotificationRead", request);
export const fetchNotificationReadKeys = () => apiRequest("fetchNotificationReadKeys", undefined);
export const fetchNotificationReport = (period: string = "DAILY") =>
  apiRequest("fetchNotificationReport", { period });
