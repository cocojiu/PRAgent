import { request } from "@/api/client";
import type { NotificationCenter } from "@/types";

export const fetchNotifications = () => request<NotificationCenter>("/api/v1/notifications");
