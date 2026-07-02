import { apiRequest } from "@/api/contracts";

export const fetchNotifications = () => apiRequest("fetchNotifications", undefined);
