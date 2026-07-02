import { apiRequest } from "@/api/contracts";

export const fetchMessageQueueHealth = () => apiRequest("fetchMessageQueueHealth", undefined);

export const requeueMessageQueueTask = (taskId: number) =>
  apiRequest("requeueMessageQueueTask", { taskId });
