import { apiRequest, type ApiRequestOptions } from "@/api/contracts";

export const fetchMessageQueueHealth = (options?: ApiRequestOptions) =>
  apiRequest("fetchMessageQueueHealth", undefined, options);

export const requeueMessageQueueTask = (taskId: number) =>
  apiRequest("requeueMessageQueueTask", { taskId });
