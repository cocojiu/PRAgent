import { request } from "@/api/client";
import type { MessageQueueHealth, MessageQueueRequeueResponse } from "@/types";

export const fetchMessageQueueHealth = () => request<MessageQueueHealth>("/api/v1/message-queue/health");

export const requeueMessageQueueTask = (taskId: number) =>
  request<MessageQueueRequeueResponse>(`/api/v1/message-queue/tasks/${taskId}/requeue`, undefined, {
    method: "POST"
  });
